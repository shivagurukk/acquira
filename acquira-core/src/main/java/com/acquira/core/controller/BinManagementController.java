package com.acquira.core.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Super-Admin BIN Management (V2026_08_08_06).
 *
 * Loads the platform-wide 6/8-digit BIN -> scheme / card type / product /
 * issuer-country mapping (ref_bin) from an uploaded CSV or Excel file.
 * CONFIGURATION ONLY this phase: nothing in ingestion or the fee engine reads
 * ref_bin yet — which source wins per tenant is tenant.card_type_source
 * ('FILE' default | 'BIN'), also config-only for now.
 *
 * Upload contract (headers case-insensitive, order-free, extra columns
 * ignored): BIN, SCHEME, CARD_TYPE (or CARD TYPE / CARDTYPE), PRODUCT
 * (or PRODUCT_CODE), COUNTRY (or ISSUER_COUNTRY), ISSUER (or ISSUER_NAME).
 * BIN must be 6 OR 8 digits (2026-08-14: was 8 only) — transaction feeds
 * mask the PAN as first-6-clear + masked + last-4-clear, so ingestion can
 * only ever extract a 6-digit BIN today; 8-digit rows stay accepted for
 * feeds that expose the full 8. Anything else is rejected and counted in
 * the response, never silently dropped.
 *
 * mode=REPLACE (default) — "fresh refresh": wipes ref_bin then loads the file.
 * mode=APPEND — upsert on BIN (last file wins per BIN).
 */
@RestController
@RequestMapping("/api/admin/bins")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BinManagementController {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(BinManagementController.class);

    // MPE deliveries load in the background: a full T068 replacement is far
    // too large to stage inside one HTTP request (UAT: 600s ingress timeout,
    // browser gave up with zero feedback). Single thread on purpose — two
    // concurrent loads would fight over ref_bin_range.
    private static final java.util.concurrent.ExecutorService MPE_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mpe-loader");
            t.setDaemon(true);
            return t;
        });

    private final JdbcTemplate jdbcTemplate;

    public BinManagementController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** MPE loads run on an in-process executor, so a restart kills them dead:
     *  any PROCESSING row found at boot is an orphan (single-replica pinned in
     *  k8s). Fail it so the duplicate guard doesn't block the re-upload for
     *  the 2h staleness window. Never blocks startup. */
    @jakarta.annotation.PostConstruct
    void failOrphanedMpeLoads() {
        try {
            int orphaned = jdbcTemplate.update(
                "UPDATE mpe_file SET status='FAILED', " +
                "error_text='Interrupted by application restart — re-upload the file' WHERE status='PROCESSING'");
            if (orphaned > 0) {
                log.warn("MPE startup recovery: marked {} orphaned PROCESSING delivery(ies) as FAILED", orphaned);
            }
        } catch (Exception e) {
            log.warn("MPE startup recovery skipped: {}", e.getMessage());
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalBins", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ref_bin", Long.class));
        out.put("byScheme", jdbcTemplate.queryForList(
            "SELECT COALESCE(scheme,'(none)') AS scheme, COUNT(*) AS bins FROM ref_bin GROUP BY scheme ORDER BY bins DESC"));
        out.put("byCountry", jdbcTemplate.queryForList(
            "SELECT COALESCE(issuer_country,'(none)') AS country, COUNT(*) AS bins FROM ref_bin GROUP BY issuer_country ORDER BY bins DESC LIMIT 15"));
        out.put("lastLoad", jdbcTemplate.queryForList(
            "SELECT source_file, MAX(loaded_at) AS loaded_at, COUNT(*) AS rows FROM ref_bin GROUP BY source_file ORDER BY MAX(loaded_at) DESC LIMIT 5"));
        // Scheme range files (ref_bin_range): totals per scheme + top countries.
        out.put("totalRanges", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ref_bin_range", Long.class));
        out.put("rangesByScheme", jdbcTemplate.queryForList(
            "SELECT scheme, COUNT(*) AS ranges, MIN(loaded_at) AS loaded_at, MAX(source_file) AS source_file " +
            "FROM ref_bin_range GROUP BY scheme ORDER BY ranges DESC"));
        out.put("rangesByCountry", jdbcTemplate.queryForList(
            "SELECT issuer_country AS country, COUNT(*) AS ranges FROM ref_bin_range " +
            "GROUP BY issuer_country ORDER BY ranges DESC LIMIT 15"));
        // Staged Mastercard MPE deliveries (T067/T068) incl. in-flight/failed.
        out.put("mpeFiles", jdbcTemplate.queryForList(
            "SELECT id, file_name, file_type, created_date, record_count, status, error_text, loaded_at " +
            "FROM mpe_file ORDER BY loaded_at DESC LIMIT 10"));
        // Data-quality alarm: every range must be 19 digits (V2026_08_09_03).
        // Non-19 rows mean data loaded by a pre-normalization build — containment
        // lookups against them silently return wrong ranges.
        out.put("malformedRanges", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_bin_range WHERE LENGTH(range_low) <> 19 OR LENGTH(range_high) <> 19", Long.class));
        return ResponseEntity.ok(out);
    }

    /**
     * Search scheme ranges. A numeric query is treated as a PAN prefix: it is
     * padded/truncated to 9 digits and matched by range containment (exactly
     * how a future BIN lookup would resolve it); otherwise matches country /
     * product / bin6.
     */
    @GetMapping("/ranges")
    public ResponseEntity<List<Map<String, Object>>> searchRanges(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        String needle = q == null ? "" : q.trim();
        // range_bin is the actual card prefix of the range; bin6 is the file's
        // LICENSED BIN, which differs from the prefix on most Visa rows (98% of
        // the live file) — showing bin6 alone reads as "wrong BINs".
        final String cols =
            "SELECT scheme, range_low, range_high, LEFT(range_low, 8) AS range_bin, bin6, region_code, " +
            "issuer_country, product_code, funding_source, card_type, source_file, loaded_at FROM ref_bin_range ";
        if (needle.isEmpty()) {
            // Default browse: real card ranges first. The Visa file carries a
            // handful of proprietary/routing entries whose range does not start
            // with a card-scheme digit (000180…, 0049…); sorted purely by
            // range_low they fill the whole first page and look like corrupt
            // data. They stay loaded (lossless), just listed last.
            return ResponseEntity.ok(jdbcTemplate.queryForList(cols +
                "ORDER BY CASE WHEN range_low ~ '^[2-6]' THEN 0 ELSE 1 END, range_low LIMIT " + lim));
        }
        if (needle.matches("\\d{4,19}")) {
            // Containment (how a PAN would resolve) PLUS every range under the
            // typed prefix — searching a 6-digit BIN must list its sub-ranges,
            // not only the single range containing <prefix>000….
            String pan19 = (needle + "0000000000000000000").substring(0, 19);
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                cols + "WHERE (range_low <= ? AND range_high >= ?) OR range_low LIKE ? ORDER BY range_low LIMIT " + lim,
                pan19, pan19, needle + "%"));
        }
        String like = "%" + needle.toUpperCase() + "%";
        return ResponseEntity.ok(jdbcTemplate.queryForList(
            cols +
            "WHERE UPPER(COALESCE(issuer_country,'')) LIKE ? OR UPPER(COALESCE(product_code,'')) LIKE ? " +
            "OR bin6 LIKE ? OR UPPER(scheme) LIKE ? ORDER BY range_low LIMIT " + lim,
            like, like, needle + "%", like));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        String needle = q == null ? "" : q.trim();
        if (needle.isEmpty()) {
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                "SELECT bin, scheme, card_type, product_code, issuer_country, issuer_name, source_file, loaded_at " +
                "FROM ref_bin ORDER BY bin LIMIT " + lim));
        }
        String like = "%" + needle.toUpperCase() + "%";
        return ResponseEntity.ok(jdbcTemplate.queryForList(
            "SELECT bin, scheme, card_type, product_code, issuer_country, issuer_name, source_file, loaded_at " +
            "FROM ref_bin WHERE bin LIKE ? OR UPPER(COALESCE(scheme,'')) LIKE ? " +
            "OR UPPER(COALESCE(issuer_country,'')) LIKE ? OR UPPER(COALESCE(product_code,'')) LIKE ? " +
            "OR UPPER(COALESCE(issuer_name,'')) LIKE ? ORDER BY bin LIMIT " + lim,
            needle + "%", like, like, like, like));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "REPLACE") String mode) throws Exception {
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String lower = name.toLowerCase();

        // FILE-TYPE ROUTING (2026-08-09 by name; 2026-08-14 also by CONTENT):
        //   VISA*        -> fixed-width Visa BIN list -> ref_bin_range (VISA)
        //   .xlsx/.xls   -> Excel -> ref_bin (8-digit manual mapping)
        //   T067/T068/T167/T168 anywhere in the name, OR binary content
        //   (zip magic / RDW length prefix) -> Mastercard MPE staged intake.
        //   Real deliveries are named like MCI.AR.T068.M...A001 — the old
        //   startsWith("t068") check missed them and the binary fell into the
        //   CSV reader, whose readLine() built the whole newline-free file
        //   into one string and OOMed the request thread.
        //   other        -> CSV -> ref_bin, but binary content is refused.
        String base = lower.replaceFirst("^.*[/\\\\]", "");
        if (base.startsWith("visa")) {
            return ResponseEntity.ok(loadVisaBinList(file, name, mode));
        }
        boolean excel = lower.endsWith(".xlsx") || lower.endsWith(".xls");
        boolean mpeName = base.contains("t067") || base.contains("t068")
                       || base.contains("t167") || base.contains("t168");
        byte[] head;
        try (var in = file.getInputStream()) {
            head = in.readNBytes(4096);
        }
        boolean zipMagic = head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        boolean rdwStart = head.length >= 4 && head[0] == 0 && head[1] == 0;
        boolean hasNul = false;
        for (byte b : head) { if (b == 0) { hasNul = true; break; } }

        if (!excel && (mpeName || zipMagic || rdwStart)) {
            Map<String, Object> staged = prepareMpeUpload(file, name);
            return staged.containsKey("error")
                ? ResponseEntity.badRequest().body(staged)
                : ResponseEntity.ok(staged);
        }
        if (!excel && hasNul) {
            log.warn("BIN upload {} rejected: binary content but no recognized format", name);
            return ResponseEntity.badRequest().body(Map.of("error",
                "File looks binary but is not a recognized format. Expected: VISA* fixed-width list, "
                + "a Mastercard T067/T068 MPE delivery (zip or raw), or a CSV/Excel BIN mapping."));
        }

        List<String[]> rows;
        try {
            rows = excel ? readExcel(file) : readCsv(file);
        } catch (IllegalArgumentException e) {
            log.warn("BIN upload {} rejected: {}", name, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File has no rows"));
        }

        // Header mapping — case-insensitive, tolerant of common variants.
        Map<String, Integer> col = headerIndex(rows.get(0));
        Integer binIdx = firstOf(col, "BIN");
        if (binIdx == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No BIN column found. Expected headers: BIN, SCHEME, CARD_TYPE, PRODUCT, COUNTRY"));
        }
        Integer schemeIdx  = firstOf(col, "SCHEME", "CARD_SCHEME", "NETWORK");
        Integer typeIdx    = firstOf(col, "CARD_TYPE", "CARDTYPE", "TYPE", "FUNDING", "FUNDING_SOURCE");
        Integer prodIdx    = firstOf(col, "PRODUCT", "PRODUCT_CODE", "PRODUCT_ID", "CARD_PRODUCT");
        Integer countryIdx = firstOf(col, "COUNTRY", "ISSUER_COUNTRY", "COUNTRY_CODE", "ISO_COUNTRY");
        Integer issuerIdx  = firstOf(col, "ISSUER", "ISSUER_NAME", "BANK", "BANK_NAME");

        List<Object[]> good = new ArrayList<>();
        List<String> rejects = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String bin = val(r, binIdx);
            if (bin.isEmpty() && rowEmpty(r)) continue;
            // Excel often renders numerics as "45717010.0" — strip a trailing .0*
            bin = bin.replaceFirst("\\.0+$", "").trim();
            if (!bin.matches("\\d{6}|\\d{8}")) {
                if (rejects.size() < 20) rejects.add("row " + (i + 1) + ": BIN '" + bin + "' is not 6 or 8 digits");
                continue;
            }
            good.add(new Object[]{
                bin,
                upperOrNull(val(r, schemeIdx)),
                upperOrNull(val(r, typeIdx)),
                upperOrNull(val(r, prodIdx)),
                upperOrNull(val(r, countryIdx)),
                emptyToNull(val(r, issuerIdx)),
                name
            });
        }
        int rejected = (rows.size() - 1) - good.size();

        boolean replace = !"APPEND".equalsIgnoreCase(mode);
        if (replace) {
            jdbcTemplate.update("DELETE FROM ref_bin");
        }
        jdbcTemplate.batchUpdate(
            "INSERT INTO ref_bin (bin, scheme, card_type, product_code, issuer_country, issuer_name, source_file, loaded_at) " +
            "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP) " +
            "ON CONFLICT (bin) DO UPDATE SET scheme=EXCLUDED.scheme, card_type=EXCLUDED.card_type, " +
            "product_code=EXCLUDED.product_code, issuer_country=EXCLUDED.issuer_country, " +
            "issuer_name=EXCLUDED.issuer_name, source_file=EXCLUDED.source_file, loaded_at=CURRENT_TIMESTAMP",
            good);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("mode", replace ? "REPLACE" : "APPEND");
        out.put("loaded", good.size());
        out.put("rejected", rejected);
        if (!rejects.isEmpty()) out.put("rejectSamples", rejects);
        out.put("totalBins", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ref_bin", Long.class));

        // Manual ref_bin product codes OVERWRITE the feed's product code for local
        // cards at ingest (card_type_source='BIN'), and tier (Standard/Premium)
        // resolves through ref_card_scheme. A product code unknown to
        // ref_card_scheme silently prices as the Premium default — surface those
        // codes now, at upload time, instead of letting them misprice quietly.
        List<Map<String, Object>> unknownProducts = jdbcTemplate.queryForList(
            "SELECT rb.product_code, COUNT(*) AS bins FROM ref_bin rb " +
            "WHERE rb.product_code IS NOT NULL AND TRIM(rb.product_code) <> '' " +
            "AND NOT EXISTS (SELECT 1 FROM ref_card_scheme rcs " +
            "  WHERE UPPER(TRIM(rcs.code)) = UPPER(TRIM(rb.product_code))) " +
            "GROUP BY rb.product_code ORDER BY bins DESC LIMIT 20");
        if (!unknownProducts.isEmpty()) {
            out.put("productCodesNotInRateVocabulary", unknownProducts);
            out.put("productCodeWarning",
                "These product codes are not in ref_card_scheme; local cards matched by them "
                + "will resolve to the Premium-default interchange tier. Add them to "
                + "ref_card_scheme (with card_subtype) or correct the upload.");
        }
        return ResponseEntity.ok(out);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        int deleted = jdbcTemplate.update("DELETE FROM ref_bin");
        log.info("BIN clear: deleted {} ref_bin rows", deleted);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /** Remove a staged MPE delivery (cascade deletes its records/directory),
     *  freeing its checksum for a reload. Never touches ref_bin_range. */
    @DeleteMapping("/mpe/{id}")
    public ResponseEntity<Map<String, Object>> deleteMpeFile(@PathVariable long id) {
        int deleted = jdbcTemplate.update("DELETE FROM mpe_file WHERE id = ?", id);
        log.info("MPE delete: mpe_file {} ({} row)", id, deleted);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // ─── Mastercard IPM MPE (T067/T068) staged intake ──────────────────
    //
    // Container + lifecycle per the Dual Message Clearing System PTL DITA
    // documentation, verified against AFS's live TT067 delivery (2026-08-09):
    //   * physical records: 4-byte big-endian length prefix + ASCII payload,
    //     with occasional 1-2 pad bytes between records
    //   * file header  : "UPDATE FILE    " + YYYYMMDD + HHMM        (T067/T167)
    //                    "REPLACEMENT FILE " + module + dates       (T068/T168)
    //   * directory    : IP0000T1 records — ts n-10 (1-10), flag (11),
    //     "IP0000T1" (12-19), subject table (20-27), name (29-55),
    //     key len (56-60), key start (61-64), rec len min/max (65-74),
    //     version (75-82), 3-char table sub-id (244-246)
    //   * data records : ts n-7 (1-7), A/I flag (8), table sub-id (9-11)
    //   * trailers     : "TRAILER RECORD " + table id (16-23) + count (26-33);
    //     final trailer table id = "TABLEZZZZ" with the total record count
    //
    // STAGE + VALIDATE ONLY: raw records are preserved verbatim per table.
    // Field-level mapping of the account-range table is deferred until the
    // T068 full replacement confirms the (older-edition) positions — the
    // inner layout in this delivery predates the current manual, and we do
    // not guess field positions (documented decision, 2026-08-09).
    //
    // ASYNC (2026-08-14): the upload request only spools the file to a temp
    // file, checksums it and registers a PROCESSING mpe_file row; scanning,
    // staging and promotion run on MPE_EXECUTOR. A full T068 replacement is
    // millions of records — the previous in-request flow held the whole file
    // (plus an unzipped copy, plus every staged row) on the heap and outran
    // both the JVM and the 600s ingress timeout, dying with no response and
    // no logs. The UI now follows progress via the staged-deliveries list.

    private Map<String, Object> prepareMpeUpload(MultipartFile file, String name) throws Exception {
        // Spool to disk, never the heap; checksum while copying.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("mpe-", ".raw");
        var md = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = new java.security.DigestInputStream(file.getInputStream(), md)) {
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        String sha256 = sb.toString();

        // Re-uploads of identical content are ALLOWED (2026-08-14, user
        // decision): the prior staged copy is replaced (sha256 is UNIQUE).
        // The only block left is an identical file mid-load right now —
        // that's a double-submit, not an intentional reload.
        Map<String, Object> dup = jdbcTemplate.query(
            "SELECT id, status, loaded_at < NOW() - INTERVAL '2 hours' AS stale FROM mpe_file WHERE sha256 = ?",
            rs -> rs.next()
                ? Map.<String, Object>of("id", rs.getLong(1), "status", rs.getString(2), "stale", rs.getBoolean(3))
                : null,
            sha256);
        if (dup != null) {
            String st = (String) dup.get("status");
            if ("PROCESSING".equals(st) && !(Boolean) dup.get("stale")) {
                java.nio.file.Files.deleteIfExists(tmp);
                return Map.of("error", "This exact file is already being processed (mpe_file id " + dup.get("id")
                    + ") — watch the staged deliveries list.");
            }
            log.info("MPE upload {}: identical content previously staged as mpe_file {} ({}) — replacing for re-upload",
                name, dup.get("id"), st);
            jdbcTemplate.update("DELETE FROM mpe_file WHERE id = ?", dup.get("id"));
        }

        long fileId = jdbcTemplate.queryForObject(
            "INSERT INTO mpe_file (file_name, file_type, sha256, status) VALUES (?, 'PENDING', ?, 'PROCESSING') RETURNING id",
            Long.class, name, sha256);
        long bytes = java.nio.file.Files.size(tmp);
        log.info("MPE upload {} ({} bytes, sha256 {}): queued as mpe_file {}", name, bytes, sha256, fileId);

        final String fName = name;
        MPE_EXECUTOR.submit(() -> {
            try {
                processMpeFile(fileId, tmp, fName);
            } catch (Throwable t) {
                log.error("MPE load FAILED for {} (mpe_file {})", fName, fileId, t);
                try {
                    String msg = t.toString();
                    jdbcTemplate.update("UPDATE mpe_file SET status='FAILED', error_text=? WHERE id=?",
                        msg.length() > 2000 ? msg.substring(0, 2000) : msg, fileId);
                } catch (Exception ignore) { /* keep the original failure */ }
            } finally {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignore) { }
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("format", "MC_MPE");
        out.put("status", "PROCESSING");
        out.put("mpeFileId", fileId);
        out.put("note", "Staging and range promotion run in the background — the staged deliveries list "
            + "shows STAGED / COUNT_MISMATCH / FAILED when done.");
        return out;
    }

    private void processMpeFile(long fileId, java.nio.file.Path rawPath, String name) throws Exception {
        long t0 = System.currentTimeMillis();
        java.nio.file.Path dataPath = rawPath;
        java.nio.file.Path unzipped = null;

        // Deliveries arrive zipped; take the first entry when so (name or magic).
        boolean isZip = name.toLowerCase().endsWith(".zip");
        if (!isZip) {
            try (var in = java.nio.file.Files.newInputStream(rawPath)) {
                byte[] magic = in.readNBytes(4);
                isZip = magic.length == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
            }
        }
        if (isZip) {
            unzipped = java.nio.file.Files.createTempFile("mpe-", ".dat");
            try (var zin = new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(rawPath))) {
                var entry = zin.getNextEntry();
                if (entry == null) throw new IllegalStateException("Zip file has no entries");
                java.nio.file.Files.copy(zin, unzipped, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                name = name + "!" + entry.getName();
                jdbcTemplate.update("UPDATE mpe_file SET file_name = ? WHERE id = ?", name, fileId);
            }
            dataPath = unzipped;
        }
        try {
            long size = java.nio.file.Files.size(dataPath);
            if (size > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("Delivery larger than 2GB unzipped is not supported");
            }
            log.info("MPE {}: scanning {} ({} bytes)", fileId, name, size);
            try (var ch = java.nio.channels.FileChannel.open(dataPath, java.nio.file.StandardOpenOption.READ)) {
                // Memory-mapped: the scan reads the file from the page cache
                // instead of a heap byte[] (a T068 can be GB-scale unzipped).
                var data = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
                scanAndStage(fileId, name, data, (int) size, t0);
            }
        } finally {
            if (unzipped != null) {
                try { java.nio.file.Files.deleteIfExists(unzipped); } catch (Exception ignore) { }
            }
        }
    }

    private void scanAndStage(long fileId, String name, java.nio.ByteBuffer data, int size, long t0) {
        // TWO-PASS SELECTIVE STAGING (2026-08-14). The original single pass
        // staged EVERY data record into mpe_record. Fine for a 371K-record
        // T067 — but a real T068 is 8.5M records (~4,300 insert batches) of
        // which promotion only ever reads the account-range table (~230K
        // records, 2.6% of the file). Those 97% wasted inserts are why a
        // T068 upload "kept running" for hours. Now:
        //   pass 1 — pure in-memory census (no DB): header, directory,
        //            per-sub counts, trailer counts, adjacency mapping.
        //   pass 2 — persist ONLY the IP0040T1 (account range) records.
        // Trailer reconciliation validates against the pass-1 COUNTED
        // numbers, so container validation is exactly as strict as before —
        // unwanted tables are counted, just never persisted.
        String[] hdr = new String[4];        // headerText, fileType, createdDate, createdTime
        Integer[] tzz = new Integer[1];      // TABLEZZZZ grand total
        Map<String, Integer> seenBySub = new LinkedHashMap<>();
        Map<String, Integer> declaredByTable = new LinkedHashMap<>();
        // TABLE <-> SUB-ID MAPPING BY TRAILER ADJACENCY: per the manual, each
        // table's records immediately precede that table's trailer, so the
        // dominant sub-id seen since the previous trailer IS that table's
        // sub-id. This is more reliable than the directory's sub-indicator
        // position, which mis-decodes on some rows of the live delivery —
        // the real T068 (D260728, verified 2026-08-14) declares sub '100'
        // for dozens of unrelated tables INCLUDING IP0040T1, so adjacency
        // is the only trustworthy source there (it resolves sub 037,
        // 224,201 records vs 229,183 declared; the gap is EBCDIC-damaged
        // sub-ids, same ~2% as TT067).
        Map<String, Integer> sinceTrailer = new LinkedHashMap<>();
        Map<String, String> derivedTableToSub = new LinkedHashMap<>();
        List<Object[]> dirRows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        long[] c = new long[2]; // [0]=accepted records, [1]=unclassified

        long padBytes = walkRecords(data, size, rec -> {
            c[0]++;
            if (rec.startsWith("UPDATE FILE")) {
                hdr[0] = rec; hdr[1] = name.toUpperCase().contains("T167") ? "T167" : "T067";
                if (rec.length() >= 27) { hdr[2] = rec.substring(15, 23).trim(); hdr[3] = rec.substring(23, 27).trim(); }
            } else if (rec.startsWith("REPLACEMENT FILE")) {
                hdr[0] = rec; hdr[1] = name.toUpperCase().contains("T168") ? "T168" : "T068";
                if (rec.length() >= 69) { hdr[2] = rec.substring(45, 54).trim(); hdr[3] = rec.substring(61, 69).trim(); }
            } else if (rec.startsWith("TRAILER RECORD")) {
                String tableId = rec.length() >= 24 ? rec.substring(15, 24).replace(" ", "").trim() : "";
                // NULs appear inside damaged trailer records — strip before parsing
                int count = parseIntSafe(rec.replace('\u0000', ' '), 25, 33);
                if ("TABLEZZZZ".equals(tableId)) {
                    tzz[0] = count;
                } else {
                    declaredByTable.merge(tableId, count, Integer::sum);
                    // dominant sub-id since the previous trailer = this table's sub
                    sinceTrailer.entrySet().stream().max(Map.Entry.comparingByValue())
                        .ifPresent(e -> derivedTableToSub.put(tableId, e.getKey()));
                    sinceTrailer.clear();
                }
            } else if (rec.length() >= 246 && rec.regionMatches(11, "IP0000T1", 0, 8)) {
                // directory record (IP0000T1)
                dirRows.add(new Object[]{
                    fileId,
                    rec.substring(19, 27).trim(),                  // subject table
                    rec.substring(28, 55).trim(),                  // subject name
                    rec.substring(243, 246).trim(),                // sub id
                    parseIntSafe(rec, 55, 60), parseIntSafe(rec, 60, 64),
                    parseIntSafe(rec, 64, 69), parseIntSafe(rec, 69, 74),
                    rec.substring(74, 82).trim()                   // version
                });
                seenBySub.merge("dir", 1, Integer::sum);
            } else if (isDataRecord(rec)) {
                String subId = rec.substring(8, 11);
                seenBySub.merge(subId, 1, Integer::sum);
                sinceTrailer.merge(subId, 1, Integer::sum);
                if (c[0] % 2_000_000 == 0) {
                    log.info("MPE {}: pass 1 scanned {} records ({}s)", fileId, c[0],
                        (System.currentTimeMillis() - t0) / 1000);
                }
            } else {
                c[1]++;
                if (problems.size() < 10) problems.add("unclassified record (" + rec.length() + " bytes): "
                        + rec.substring(0, Math.min(40, rec.length())));
            }
        });
        long total = c[0], unknown = c[1];

        String fileType = hdr[1];
        if (fileType == null) {
            throw new IllegalStateException(
                "No documented IPM MPE header found (\"UPDATE FILE\" / \"REPLACEMENT FILE\") — not an MPE delivery?");
        }

        // Reconcile per-table trailer counts against the pass-1 census. Prefer
        // the adjacency-derived table->sub mapping; fall back to the
        // directory's sub-indicator field. Data records for IP0000T1 are the
        // directory rows themselves. EBCDIC-corrupted records land under
        // '@@'-damaged sub-ids, so their tables report short — expected,
        // surfaced as COUNT_MISMATCH, never guessed at.
        Map<String, String> tableToSub = new LinkedHashMap<>();
        for (Object[] d : dirRows) tableToSub.put((String) d[1], (String) d[3]);
        tableToSub.putAll(derivedTableToSub);
        for (var e : declaredByTable.entrySet()) {
            String table = e.getKey();
            int declared = e.getValue();
            int seen = "IP0000T1".equals(table)
                ? seenBySub.getOrDefault("dir", 0)
                : seenBySub.getOrDefault(tableToSub.getOrDefault(table, "???"), 0);
            if (seen != declared) {
                problems.add("count mismatch " + table + ": trailer declares " + declared + ", seen " + seen);
            }
        }
        String status = problems.stream().anyMatch(p -> p.startsWith("count mismatch")) ? "COUNT_MISMATCH" : "STAGED";

        // PASS 2 — persist only the account-range table's records.
        String ip40SubStage = tableToSub.get("IP0040T1");
        int staged = 0;
        if (ip40SubStage != null) {
            final String recSql = "INSERT INTO mpe_record (file_id, sub_id, active_flag, effective_raw, record_text) VALUES (?,?,?,?,?)";
            List<Object[]> recBatch = new ArrayList<>();
            long[] stagedCnt = new long[1];
            walkRecords(data, size, rec -> {
                if (!isDataRecord(rec) || !ip40SubStage.equals(rec.substring(8, 11))) return;
                // EBCDIC-damaged records carry raw 0x00 bytes and Postgres
                // rejects NUL in text ("invalid byte sequence for encoding
                // UTF8: 0x00" killed mpe_file 2 after 372s of staging).
                // Store NUL as space: the promote-side strict \d{19}
                // validation still quarantines the damaged record either way.
                recBatch.add(new Object[]{ fileId, ip40SubStage, String.valueOf(rec.charAt(7)),
                    rec.substring(0, 7), stripNul(rec) });
                stagedCnt[0]++;
                if (recBatch.size() >= 2000) { jdbcTemplate.batchUpdate(recSql, recBatch); recBatch.clear(); }
                if (stagedCnt[0] % 100_000 == 0) {
                    log.info("MPE {}: pass 2 staged {} IP0040T1 records ({}s)", fileId, stagedCnt[0],
                        (System.currentTimeMillis() - t0) / 1000);
                }
            });
            if (!recBatch.isEmpty()) jdbcTemplate.batchUpdate(recSql, recBatch);
            staged = (int) stagedCnt[0];
        } else {
            problems.add("IP0040T1 has no resolvable sub-id (no adjacency mapping, directory unusable) — nothing staged");
            status = "COUNT_MISMATCH";
        }

        // staged_count = records SEEN in the file for that table (only
        // IP0040T1's are persisted since 2026-08-14) — it stays the number
        // the trailer reconciliation above compared against.
        for (Object[] d : dirRows) {
            String sub = tableToSub.getOrDefault((String) d[1], (String) d[3]);
            jdbcTemplate.update(
                "INSERT INTO mpe_table_directory (file_id, subject_table, subject_name, sub_id, key_length, key_start, " +
                "rec_len_min, rec_len_max, version, declared_count, staged_count) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                d[0], d[1], d[2], sub, d[4], d[5], d[6], d[7], d[8],
                declaredByTable.get((String) d[1]), seenBySub.getOrDefault(sub, 0));
        }
        String problemText = problems.isEmpty() ? null : stripNul(String.join("; ", problems));
        if (problemText != null && problemText.length() > 2000) problemText = problemText.substring(0, 2000);
        jdbcTemplate.update(
            "UPDATE mpe_file SET file_type=?, header_text=?, created_date=?, created_time=?, record_count=?, trailer_total=?, status=?, error_text=? WHERE id=?",
            fileType, stripNul(hdr[0]), hdr[2], hdr[3], total, tzz[0], status, problemText, fileId);
        log.info("MPE {}: {} scan done — {} records seen, {} IP0040T1 staged, {} unknown, {} pad bytes, status {} ({}s)",
            fileId, fileType, total, staged, unknown, padBytes, status, (System.currentTimeMillis() - t0) / 1000);

        // ─── PROMOTION into ref_bin_range (business-confirmed 2026-08-09) ───
        //   T068 = full replacement: DELETE all MASTERCARD rows, insert every
        //          active IP0040T1 record from this file.
        //   T067 = delta: 'A' upserts by (range_low, GCMS product) — the
        //          record-generating key per the manual — 'I' removes that
        //          key. No mass delete.
        //   T167/T168 (test) = stage only, never touch reference data.
        String ip40Sub = tableToSub.get("IP0040T1");
        if (ip40Sub != null && ("T067".equals(fileType) || "T068".equals(fileType))) {
            Map<String, Object> promotion = promoteMcRanges(fileId, name, ip40Sub, "T068".equals(fileType));
            log.info("MPE {}: Mastercard range promotion {}", fileId, promotion);
        } else {
            log.info("MPE {}: staged only, no promotion (fileType {}, IP0040T1 sub {})", fileId, fileType, ip40Sub);
        }
        log.info("MPE {}: load complete in {}s", fileId, (System.currentTimeMillis() - t0) / 1000);
    }

    /** Postgres text columns reject U+0000; EBCDIC-damaged records carry raw
     *  NUL bytes. Space keeps positions intact for the strict promote-side
     *  validation (which then quarantines the damaged record anyway). */
    private static String stripNul(String s) {
        return s == null ? null : s.replace('\u0000', ' ');
    }

    /** True when the record matches the documented data-record shape:
     *  7-digit effective timestamp, then A(ctive)/I(nactive), then sub-id. */
    private static boolean isDataRecord(String rec) {
        return rec.length() >= 11 && rec.substring(0, 7).chars().allMatch(Character::isDigit)
            && (rec.charAt(7) == 'A' || rec.charAt(7) == 'I');
    }

    /**
     * SELF-HEALING RDW WALK: pad bytes appear between records in the live
     * delivery, and a mis-read length must never abort the load. At each
     * byte: if a plausible RDW (00 00 + sane length) starts a record we can
     * CLASSIFY, accept it and jump; otherwise advance one byte. A record is
     * accepted only when it matches a documented shape (or is short and
     * printable, so genuinely new table shapes surface instead of
     * vanishing) — a corrupt length can't swallow the records after it.
     * Absolute buffer reads only, so the same mapped buffer can be walked
     * twice. Returns the number of skipped pad/garbage bytes.
     */
    private static long walkRecords(java.nio.ByteBuffer data, int size, java.util.function.Consumer<String> sink) {
        long padBytes = 0;
        int off = 0;
        while (off + 12 <= size) {
            if (!(data.get(off) == 0 && data.get(off + 1) == 0)) { off++; padBytes++; continue; }
            int len = ((data.get(off) & 0xFF) << 24) | ((data.get(off + 1) & 0xFF) << 16)
                    | ((data.get(off + 2) & 0xFF) << 8) | (data.get(off + 3) & 0xFF);
            if (len < 12 || len > 20000 || off + 4 + len > size) { off++; padBytes++; continue; }
            byte[] recBytes = new byte[len];
            data.get(off + 4, recBytes);
            String rec = new String(recBytes, java.nio.charset.StandardCharsets.US_ASCII);
            boolean known = rec.startsWith("UPDATE FILE") || rec.startsWith("REPLACEMENT FILE")
                || rec.startsWith("TRAILER RECORD")
                || (rec.length() >= 246 && rec.regionMatches(11, "IP0000T1", 0, 8))
                || isDataRecord(rec);
            boolean printable = len <= 1000 && rec.chars().limit(8).allMatch(ch -> ch >= 0x20 || ch == 0);
            if (!known && !printable) { off++; padBytes++; continue; }
            off += 4 + len;
            sink.accept(rec);
        }
        return padBytes;
    }


    /**
     * Decode staged IP0040T1 records (compressed layout, positions verified
     * against the live delivery AND the PTL manual: low 12-30, GCMS product
     * 31-33, high 34-52, program 53-55, member 58-68, product type 69,
     * country numeric 80-82, region 83) and promote them into ref_bin_range.
     *
     * fullReplace (T068): wipe scheme MASTERCARD first, then insert actives.
     * delta (T067): A = upsert by (range_low, product), I = delete that key.
     * Corrupted records (EBCDIC-space damage) fail strict validation and are
     * counted as quarantined — never guessed at.
     */
    private Map<String, Object> promoteMcRanges(long fileId, String sourceFile, String subId, boolean fullReplace) {
        // ISO 3166-1 numeric -> alpha-2. NOT ref_country.iso_numeric — that
        // column holds ISO 4217 CURRENCY numerics (BR=986/BRL not 076), which
        // silently blanked issuer_country for every redenominated-currency
        // country (V2026_08_14_02 added the real codes).
        Map<String, String> numToAlpha2 = new HashMap<>();
        jdbcTemplate.query("SELECT iso3166_numeric, country_code FROM ref_country WHERE iso3166_numeric IS NOT NULL",
            rs -> { numToAlpha2.put(rs.getString(1).trim(), rs.getString(2)); });
        // Legacy/transitional codes Mastercard still emits in live deliveries.
        numToAlpha2.putIfAbsent("280", "DE");  // pre-1990 Germany, still used by old members
        numToAlpha2.putIfAbsent("736", "SD");  // Sudan before 2011 split
        numToAlpha2.putIfAbsent("891", "RS");  // Serbia & Montenegro
        numToAlpha2.putIfAbsent("810", "RU");  // USSR
        numToAlpha2.putIfAbsent("200", "CZ");  // Czechoslovakia

        int inserted = 0, updated = 0, deleted = 0, quarantined = 0;
        if (fullReplace) {
            int wiped = jdbcTemplate.update("DELETE FROM ref_bin_range WHERE scheme = 'MASTERCARD'");
            log.info("MPE {}: T068 full replace — deleted {} existing MASTERCARD ranges", fileId, wiped);
        }
        final String insSql =
            "INSERT INTO ref_bin_range (scheme, range_low, range_high, bin6, region_code, issuer_country, " +
            "product_code, funding_source, card_type, source_file) VALUES ('MASTERCARD',?,?,?,?,?,?,NULL,?,?)";

        // Page by id: a T068's full account-range table is too large to hold
        // in one queryForList (that heap spike is what killed UAT loads).
        //
        // FULL-REPLACE DEDUP (2026-08-14): the real T068 carries ~1,200
        // duplicate (range_low, GCMS product) keys — the record-generating
        // key per the manual. Blind batch inserts duplicated those ranges in
        // ref_bin_range (no unique constraint there) and broke the delta
        // path's delete-by-key assumption. Now full replace collects rows in
        // a last-wins map keyed (range_low, product) — same winner as the
        // T067 delta's delete-then-insert — and an 'I' record removes the
        // key instead of issuing a DELETE that can't see the not-yet-flushed
        // batch. ~214K Object[] rows is a few tens of MB, safely below the
        // queryForList-everything spike this replaced.
        Map<String, Object[]> replaceRows = fullReplace ? new LinkedHashMap<>() : null;
        List<Object[]> batch = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, active_flag, record_text FROM mpe_record " +
                "WHERE file_id = ? AND sub_id = ? AND id > ? ORDER BY id LIMIT 20000", fileId, subId, lastId);
            if (rows.isEmpty()) break;
            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
            for (Map<String, Object> r : rows) {
                String rec = (String) r.get("record_text");
                String flag = (String) r.get("active_flag");
                if (rec.length() < 83) { quarantined++; continue; }
                String low = rec.substring(11, 30);
                String gcms = rec.substring(30, 33).trim();
                String high = rec.substring(33, 52);
                String ctryN = rec.substring(79, 82).trim();
                String region = rec.substring(82, 83).trim();
                if (!low.matches("\\d{19}") || !high.matches("\\d{19}") || gcms.isEmpty()) { quarantined++; continue; }
                String alpha2 = numToAlpha2.get(ctryN);
                String bin6 = low.substring(0, 6);

                if (fullReplace) {
                    String key = low + "|" + gcms;
                    if ("I".equals(flag)) {
                        if (replaceRows.remove(key) != null) deleted++;
                    } else {
                        replaceRows.put(key, new Object[]{ low, high, bin6, region.isEmpty() ? null : region,
                            alpha2, gcms, mcCardType(gcms), sourceFile });
                    }
                } else if ("I".equals(flag)) {
                    deleted += jdbcTemplate.update(
                        "DELETE FROM ref_bin_range WHERE scheme='MASTERCARD' AND range_low=? AND COALESCE(product_code,'')=?",
                        low, gcms);
                } else {
                    int del = jdbcTemplate.update(
                        "DELETE FROM ref_bin_range WHERE scheme='MASTERCARD' AND range_low=? AND COALESCE(product_code,'')=?",
                        low, gcms);
                    jdbcTemplate.update(insSql, low, high, bin6, region.isEmpty() ? null : region,
                        alpha2, gcms, mcCardType(gcms), sourceFile);
                    if (del > 0) updated++; else inserted++;
                }
            }
            log.info("MPE {}: promotion progress — {} collected/inserted, {} updated, {} deleted, {} quarantined",
                fileId, fullReplace ? replaceRows.size() : inserted, updated, deleted, quarantined);
        }
        if (fullReplace) {
            for (Object[] row : replaceRows.values()) {
                batch.add(row);
                if (batch.size() >= 5000) { jdbcTemplate.batchUpdate(insSql, batch); inserted += batch.size(); batch.clear(); }
            }
        }
        if (!batch.isEmpty()) { jdbcTemplate.batchUpdate(insSql, batch); inserted += batch.size(); }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", fullReplace ? "FULL_REPLACE" : "DELTA");
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("deleted", deleted);
        out.put("quarantined", quarantined);
        out.put("totalMastercardRanges", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_bin_range WHERE scheme='MASTERCARD'", Long.class));
        return out;
    }

    /**
     * GCMS product ID -> coarse card type. Conservative, documented mapping:
     * MD* (Debit Mastercard family) and CIR (Cirrus) are debit; the listed
     * credit products are credit; anything unrecognized stays NULL rather
     * than guessed — IP0016T1 Brand Product (absent from this delivery) is
     * the authoritative source and can refine this later.
     */
    // Mapped 2026-08-14 from Mastercard's licensed product-code list (per
    // Stripe's and TabaPay's published copies of the Mastercard table),
    // business-approved. Notable corrections vs the first cut: MRG/MRW/MRJ/MRK
    // are PREPAID (Prepaid Non-US family), not credit. The T-series are
    // "immediate debit" variants of credit products -> DEBIT. B2B/VIP virtual
    // products (MT*, MV*, MB[A,G-Z], WA*, WB*, FI*), private label (PV*) and
    // co-badge VIS stay NULL — funding is genuinely undefined for them.
    private static final Set<String> MC_CREDIT = Set.of(
        "MCC", "MCS", "MCG", "MPL", "MCB", "MCO", "MCW", "MWE", "MEB",
        "MAB", "MBK", "MPB", "MCF",
        "MCE", "MCH", "MCP", "MCT", "MCV", "MNW", "MUW", "MWB", "MWJ", "MWK",
        "MWO", "MAC", "MAJ", "MEO", "MFB", "MFD", "MFH", "MFE", "MFL", "MFW",
        "BPE", "BPL", "MBE", "MRO", "MRF", "DCO", "DBS", "WBE", "TEB", "MRP", "MWR");

    private static final Set<String> MC_DEBIT = Set.of(
        "CIR", "MSI", "MSB", "MBS", "MBD", "ACS", "MET", "BPD", "MFI", "MIU",
        // Maestro core + delayed debit
        "MOC", "MOG", "MOP", "MOW", "OLB", "OLG", "OLP", "OLS", "OLW",
        // immediate-debit variants of the credit lineup
        "TBE", "TCB", "TCC", "TCE", "TCF", "TCG", "TCO", "TCP", "TCS", "TCW",
        "TEC", "TEO", "TNF", "TNW", "TPB", "TPL", "TWB",
        // salary / delayed-debit consumer
        "DAG", "DAP", "DAS", "DOS", "SAL", "SAG", "SAP", "SAS", "SOS",
        "DLG", "DLH", "DLP", "DLS",
        // digital debit + HELOC/HSA debit
        "MKA", "MKB", "MKC", "MKD", "MHB", "MHD", "MHL", "MHM", "MHN", "MHH");

    private static final Set<String> MC_PREPAID = Set.of(
        "MPA", "MPD", "MPF", "MPG", "MPJ", "MPK", "MPM", "MPN", "MPO", "MPP",
        "MPR", "MPT", "MPV", "MPW", "MPX", "MPY", "MPZ",
        "MRB", "MRC", "MRD", "MRG", "MRH", "MRJ", "MRK", "MRL", "MRS", "MRW",
        "MSA", "MSF", "MSG", "MSJ", "MSM", "MSN", "MSO", "MSR", "MST", "MSV",
        "MSW", "MSX", "MSY", "MSZ",
        "MBB", "MBC", "MBF", "MBP", "MAQ", "MGP", "MGS", "MDE", "MHA",
        "MIA", "MIK", "MIP", "MUS", "MWF", "MWP", "WPD", "SUR", "SBP",
        "CPP", "CPS", "DPP", "DPS", "TPM", "MTP");

    private static String mcCardType(String gcms) {
        if (MC_PREPAID.contains(gcms)) return "PREPAID";
        if (MC_DEBIT.contains(gcms)) return "DEBIT";
        // MD* is the Debit Mastercard family (MDE = Essential Prepaid above)
        if (gcms.startsWith("MD")) return "DEBIT";
        if (MC_CREDIT.contains(gcms)) return "CREDIT";
        return null;
    }

    private static int parseIntSafe(String s, int from, int to) {
        try {
            return Integer.parseInt(s.substring(from, Math.min(to, s.length())).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ─── Visa BIN list (fixed-width) ───────────────────────────────────
    //
    // Column positions were verified EMPIRICALLY against the full AFS file
    // (593,505 lines, per-position value profiling on 2026-08-09), 0-based:
    //   0-8   account range HIGH (9 digits)
    //   12-20 account range LOW  (9 digits)
    //   35-40 licensed BIN (6 digits)
    //   42    Visa region digit (1=US ... 6=CEMEA)
    //   43-44 issuer country, ISO alpha-2
    //   58-59 Visa product ID (letter + optional digit: F, N, C, G1, N3, ...)
    //   69    funding source: C=credit D=debit P=prepaid H=charge R=deferred debit
    // Line lengths vary 70-79 (optional trailing fields); leading layout is fixed.
    // Rows failing validation are counted and sampled, never silently dropped.

    private Map<String, Object> loadVisaBinList(MultipartFile file, String name, String mode) throws Exception {
        long t0 = System.currentTimeMillis();
        log.info("Visa BIN list load starting: {} ({} bytes, mode {})", name, file.getSize(), mode);
        List<Object[]> batch = new ArrayList<>();
        List<String> rejects = new ArrayList<>();
        Map<String, Integer> byCountry = new HashMap<>();
        int lineNo = 0, loaded = 0, rejected = 0;

        // ALWAYS full refresh, regardless of the UI mode dropdown: the Visa
        // BIN list is the complete range set every time (no delta deliveries
        // exist), and ref_bin_range has no Visa upsert key — an APPEND here
        // would duplicate all ~593K rows. Business-confirmed 2026-08-09.
        if ("APPEND".equalsIgnoreCase(mode)) {
            log.info("Visa BIN list: APPEND requested but Visa always loads as full refresh — replacing");
        }
        jdbcTemplate.update("DELETE FROM ref_bin_range WHERE scheme = 'VISA'");

        final String insert =
            "INSERT INTO ref_bin_range (scheme, range_low, range_high, bin6, region_code, " +
            "issuer_country, product_code, funding_source, card_type, source_file) " +
            "VALUES ('VISA',?,?,?,?,?,?,?,?,?)";

        try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                if (line.length() < 70) {
                    rejected++;
                    if (rejects.size() < 20) rejects.add("line " + lineNo + ": too short (" + line.length() + " chars)");
                    continue;
                }
                String high = line.substring(0, 9);
                String low = line.substring(12, 21);
                String bin6 = line.substring(35, 41);
                String region = line.substring(42, 43).trim();
                String country = line.substring(43, 45).trim();
                String product = line.substring(58, 60).trim();
                String funding = line.substring(69, 70).trim();

                if (!high.matches("\\d{9}") || !low.matches("\\d{9}") || !country.matches("[A-Z]{2}")) {
                    rejected++;
                    if (rejects.size() < 20) {
                        rejects.add("line " + lineNo + ": bad range/country (high='" + high
                            + "' low='" + low + "' country='" + country + "')");
                    }
                    continue;
                }
                batch.add(new Object[]{
                    // normalized to 19 digits (V2026_08_09_03): shared containment
                    // lookup with Mastercard's 19-digit account ranges
                    low + "0000000000", high + "9999999999",
                    bin6.matches("\\d{6}") ? bin6 : null,
                    region.isEmpty() ? null : region,
                    country,
                    product.isEmpty() ? null : product,
                    funding.isEmpty() ? null : funding,
                    visaCardType(funding),
                    name
                });
                byCountry.merge(country, 1, Integer::sum);
                loaded++;
                if (batch.size() >= 5000) {
                    jdbcTemplate.batchUpdate(insert, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(insert, batch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("format", "VISA_BIN_LIST");
        out.put("mode", "REPLACE");
        out.put("loaded", loaded);
        out.put("rejected", rejected);
        if (!rejects.isEmpty()) out.put("rejectSamples", rejects);
        // Validation summary: reject-rate and country sanity are the drift alarms.
        out.put("bahrainRanges", byCountry.getOrDefault("BH", 0));
        out.put("distinctCountries", byCountry.size());
        out.put("totalRanges", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_bin_range WHERE scheme='VISA'", Long.class));
        log.info("Visa BIN list load done: {} loaded, {} rejected, {} countries ({}s)",
            loaded, rejected, byCountry.size(), (System.currentTimeMillis() - t0) / 1000);
        return out;
    }

    /** Visa funding source letter -> Acquira card-type bucket. H (charge) and
     *  R (deferred debit) settle like credit; raw letter is kept alongside. */
    private static String visaCardType(String funding) {
        switch (funding) {
            case "D": return "DEBIT";
            case "P": return "PREPAID";
            case "C": case "H": case "R": return "CREDIT";
            default: return null;
        }
    }

    // ─── parsing helpers ───────────────────────────────────────────────

    private static List<String[]> readCsv(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // A newline-free binary file makes readLine() accumulate the
                // whole upload into one string — cap it instead of OOMing.
                if (line.length() > 200_000) {
                    throw new IllegalArgumentException(
                        "File does not look like a CSV (a single line exceeds 200KB — binary upload?)");
                }
                if (line.isBlank()) continue;
                rows.add(splitCsvLine(line));
            }
        }
        return rows;
    }

    /** Minimal CSV split with double-quote support (BIN files are simple grids). */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            } else if (c == ',' && !inQ) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static List<String[]> readExcel(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            var sheet = wb.getSheetAt(0);
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                short last = row.getLastCellNum();
                if (last < 0) continue;
                String[] cells = new String[last];
                boolean any = false;
                for (int c = 0; c < last; c++) {
                    var cell = row.getCell(c);
                    cells[c] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!cells[c].isEmpty()) any = true;
                }
                if (any) rows.add(cells);
            }
        }
        return rows;
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String key = header[i] == null ? "" : header[i].trim().toUpperCase().replaceAll("[\\s-]+", "_");
            if (!key.isEmpty()) idx.putIfAbsent(key, i);
        }
        return idx;
    }

    private static Integer firstOf(Map<String, Integer> col, String... names) {
        for (String n : names) if (col.containsKey(n)) return col.get(n);
        return null;
    }

    private static String val(String[] row, Integer idx) {
        if (idx == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private static boolean rowEmpty(String[] row) {
        for (String c : row) if (c != null && !c.trim().isEmpty()) return false;
        return true;
    }

    private static String upperOrNull(String s) {
        return s == null || s.isEmpty() ? null : s.toUpperCase();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
