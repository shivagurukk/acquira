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
 * Loads the platform-wide 8-digit BIN -> scheme / card type / product /
 * issuer-country mapping (ref_bin) from an uploaded CSV or Excel file.
 * CONFIGURATION ONLY this phase: nothing in ingestion or the fee engine reads
 * ref_bin yet — which source wins per tenant is tenant.card_type_source
 * ('FILE' default | 'BIN'), also config-only for now.
 *
 * Upload contract (headers case-insensitive, order-free, extra columns
 * ignored): BIN, SCHEME, CARD_TYPE (or CARD TYPE / CARDTYPE), PRODUCT
 * (or PRODUCT_CODE), COUNTRY (or ISSUER_COUNTRY), ISSUER (or ISSUER_NAME).
 * BIN must be EXACTLY 8 digits — shorter/longer/non-numeric rows are rejected
 * and counted in the response, never silently dropped.
 *
 * mode=REPLACE (default) — "fresh refresh": wipes ref_bin then loads the file.
 * mode=APPEND — upsert on BIN (last file wins per BIN).
 */
@RestController
@RequestMapping("/api/admin/bins")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BinManagementController {

    private final JdbcTemplate jdbcTemplate;

    public BinManagementController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        // Staged Mastercard MPE deliveries (T067/T068) awaiting field mapping.
        out.put("mpeFiles", jdbcTemplate.queryForList(
            "SELECT id, file_name, file_type, created_date, record_count, status, loaded_at " +
            "FROM mpe_file ORDER BY loaded_at DESC LIMIT 10"));
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
        if (needle.isEmpty()) {
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                "SELECT scheme, range_low, range_high, bin6, region_code, issuer_country, product_code, " +
                "funding_source, card_type, source_file, loaded_at FROM ref_bin_range ORDER BY range_low LIMIT " + lim));
        }
        if (needle.matches("\\d{4,19}")) {
            String pan9 = (needle + "0000000000000000000").substring(0, 19);
            return ResponseEntity.ok(jdbcTemplate.queryForList(
                "SELECT scheme, range_low, range_high, bin6, region_code, issuer_country, product_code, " +
                "funding_source, card_type, source_file, loaded_at FROM ref_bin_range " +
                "WHERE range_low <= ? AND range_high >= ? ORDER BY range_low LIMIT " + lim,
                pan9, pan9));
        }
        String like = "%" + needle.toUpperCase() + "%";
        return ResponseEntity.ok(jdbcTemplate.queryForList(
            "SELECT scheme, range_low, range_high, bin6, region_code, issuer_country, product_code, " +
            "funding_source, card_type, source_file, loaded_at FROM ref_bin_range " +
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

        // FILE-TYPE ROUTING BY NAME (2026-08-09, business-confirmed):
        //   VISA*  -> fixed-width Visa BIN list -> ref_bin_range (scheme VISA)
        //   T067*  -> Mastercard SMS parameter extract: parser deferred until
        //             the SMS layout manual is provided; reject with guidance.
        //   other  -> CSV/XLSX -> ref_bin (8-digit manual mapping), unchanged.
        String base = lower.replaceFirst("^.*[/\\\\]", "");
        if (base.startsWith("visa")) {
            return ResponseEntity.ok(loadVisaBinList(file, name, mode));
        }
        if (base.startsWith("t067") || base.startsWith("tt067")
                || base.startsWith("t068") || base.startsWith("tt068")
                || base.startsWith("t167") || base.startsWith("tt167")
                || base.startsWith("t168") || base.startsWith("tt168")) {
            return ResponseEntity.ok(stageMpeFile(file, name));
        }

        List<String[]> rows;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            rows = readExcel(file);
        } else {
            rows = readCsv(file);
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
            if (!bin.matches("\\d{8}")) {
                if (rejects.size() < 20) rejects.add("row " + (i + 1) + ": BIN '" + bin + "' is not exactly 8 digits");
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
        return ResponseEntity.ok(out);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        int deleted = jdbcTemplate.update("DELETE FROM ref_bin");
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

    private Map<String, Object> stageMpeFile(MultipartFile file, String name) throws Exception {
        byte[] data = file.getBytes();
        // Deliveries arrive zipped; take the first entry when so.
        if (name.toLowerCase().endsWith(".zip")) {
            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                var entry = zin.getNextEntry();
                if (entry == null) return Map.of("error", "Zip file has no entries");
                data = zin.readAllBytes();
                name = name + "!" + entry.getName();
            }
        }
        String sha256;
        {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var h = md.digest(data);
            var sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            sha256 = sb.toString();
        }
        Integer dup = jdbcTemplate.query("SELECT id FROM mpe_file WHERE sha256 = ?",
                rs -> rs.next() ? rs.getInt(1) : null, sha256);
        if (dup != null) {
            return Map.of("error", "Duplicate delivery: this exact file content is already staged (mpe_file id "
                    + dup + "). Delete it first if a reload is intended.");
        }

        String headerText = null, fileType = null, createdDate = null, createdTime = null;
        Integer trailerTotal = null;
        Map<String, Integer> stagedBySub = new LinkedHashMap<>();
        Map<String, Integer> declaredByTable = new LinkedHashMap<>();
        // TABLE <-> SUB-ID MAPPING BY TRAILER ADJACENCY: per the manual, each
        // table's records immediately precede that table's trailer, so the
        // dominant sub-id seen since the previous trailer IS that table's
        // sub-id. This is more reliable than the directory's sub-indicator
        // position, which mis-decodes on some rows of the live delivery.
        Map<String, Integer> sinceTrailer = new LinkedHashMap<>();
        Map<String, String> derivedTableToSub = new LinkedHashMap<>();
        List<Object[]> dirRows = new ArrayList<>();
        List<Object[]> recBatch = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int total = 0, padBytes = 0, unknown = 0;

        long fileId = jdbcTemplate.queryForObject(
            "INSERT INTO mpe_file (file_name, file_type, sha256, status) VALUES (?, 'PENDING', ?, 'STAGED') RETURNING id",
            Long.class, name, sha256);
        final String recSql = "INSERT INTO mpe_record (file_id, sub_id, active_flag, effective_raw, record_text) VALUES (?,?,?,?,?)";

        // SELF-HEALING SCAN: pad bytes appear between records in the live
        // delivery, and a mis-read length must never abort the load. At each
        // byte: if a plausible RDW (00 00 + sane length) starts a record we
        // can CLASSIFY, accept it and jump; otherwise advance one byte. A
        // record is accepted only when it matches a documented shape, so a
        // corrupt length can't swallow the records that follow it.
        int off = 0;
        while (off + 12 <= data.length) {
            if (!(data[off] == 0 && data[off + 1] == 0)) { off++; padBytes++; continue; }
            int len = ((data[off] & 0xFF) << 24) | ((data[off+1] & 0xFF) << 16) | ((data[off+2] & 0xFF) << 8) | (data[off+3] & 0xFF);
            if (len < 12 || len > 20000 || off + 4 + len > data.length) { off++; padBytes++; continue; }
            String rec = new String(data, off + 4, len, java.nio.charset.StandardCharsets.US_ASCII);
            boolean known = rec.startsWith("UPDATE FILE") || rec.startsWith("REPLACEMENT FILE")
                || rec.startsWith("TRAILER RECORD")
                || (rec.length() >= 246 && rec.regionMatches(11, "IP0000T1", 0, 8))
                || (rec.length() >= 11 && rec.substring(0, 7).chars().allMatch(Character::isDigit)
                    && (rec.charAt(7) == 'A' || rec.charAt(7) == 'I'));
            // Unknown-but-printable short records are accepted (and counted) so
            // genuinely new table shapes surface instead of vanishing; anything
            // else is treated as misalignment and re-scanned byte-wise.
            boolean printable = len <= 1000 && rec.chars().limit(8).allMatch(c -> c >= 0x20 || c == 0);
            if (!known && !printable) { off++; padBytes++; continue; }
            off += 4 + len;
            total++;

            if (rec.startsWith("UPDATE FILE")) {
                headerText = rec; fileType = name.toUpperCase().contains("T167") ? "T167" : "T067";
                if (rec.length() >= 27) { createdDate = rec.substring(15, 23).trim(); createdTime = rec.substring(23, 27).trim(); }
            } else if (rec.startsWith("REPLACEMENT FILE")) {
                headerText = rec; fileType = name.toUpperCase().contains("T168") ? "T168" : "T068";
                if (rec.length() >= 69) { createdDate = rec.substring(45, 54).trim(); createdTime = rec.substring(61, 69).trim(); }
            } else if (rec.startsWith("TRAILER RECORD")) {
                String tableId = rec.length() >= 24 ? rec.substring(15, 24).replace(" ", "").trim() : "";
                int count = parseIntSafe(rec.replace(" ", " "), 25, 33);
                if ("TABLEZZZZ".equals(tableId)) {
                    trailerTotal = count;
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
                stagedBySub.merge("dir", 1, Integer::sum);
            } else if (rec.length() >= 11 && rec.substring(0, 7).chars().allMatch(Character::isDigit)
                    && (rec.charAt(7) == 'A' || rec.charAt(7) == 'I')) {
                String subId = rec.substring(8, 11);
                recBatch.add(new Object[]{ fileId, subId, String.valueOf(rec.charAt(7)), rec.substring(0, 7), rec });
                stagedBySub.merge(subId, 1, Integer::sum);
                sinceTrailer.merge(subId, 1, Integer::sum);
                if (recBatch.size() >= 2000) { jdbcTemplate.batchUpdate(recSql, recBatch); recBatch.clear(); }
            } else {
                unknown++;
                if (problems.size() < 10) problems.add("unclassified record (" + len + " bytes): "
                        + rec.substring(0, Math.min(40, rec.length())));
            }
        }
        if (!recBatch.isEmpty()) jdbcTemplate.batchUpdate(recSql, recBatch);

        if (fileType == null) {
            jdbcTemplate.update("DELETE FROM mpe_file WHERE id = ?", fileId);
            return Map.of("error", "No documented IPM MPE header found (\"UPDATE FILE\" / \"REPLACEMENT FILE\") — not an MPE delivery?");
        }

        // Reconcile per-table trailer counts against staged counts. Prefer the
        // adjacency-derived table->sub mapping; fall back to the directory's
        // sub-indicator field. Data records for IP0000T1 are the directory
        // rows themselves.
        Map<String, String> tableToSub = new LinkedHashMap<>();
        for (Object[] d : dirRows) tableToSub.put((String) d[1], (String) d[3]);
        tableToSub.putAll(derivedTableToSub);
        for (var e : declaredByTable.entrySet()) {
            String table = e.getKey();
            int declared = e.getValue();
            int staged = "IP0000T1".equals(table)
                ? stagedBySub.getOrDefault("dir", 0)
                : stagedBySub.getOrDefault(tableToSub.getOrDefault(table, "???"), 0);
            if (staged != declared) {
                problems.add("count mismatch " + table + ": trailer declares " + declared + ", staged " + staged);
            }
        }
        int dataTotal = total; // trailer TABLEZZZZ counts all records incl. directory+trailers per doc? validate loosely
        String status = problems.stream().anyMatch(p -> p.startsWith("count mismatch")) ? "COUNT_MISMATCH" : "STAGED";

        for (Object[] d : dirRows) {
            String sub = tableToSub.getOrDefault((String) d[1], (String) d[3]);
            jdbcTemplate.update(
                "INSERT INTO mpe_table_directory (file_id, subject_table, subject_name, sub_id, key_length, key_start, " +
                "rec_len_min, rec_len_max, version, declared_count, staged_count) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                d[0], d[1], d[2], sub, d[4], d[5], d[6], d[7], d[8],
                declaredByTable.get((String) d[1]), stagedBySub.getOrDefault(sub, 0));
        }
        jdbcTemplate.update(
            "UPDATE mpe_file SET file_type=?, header_text=?, created_date=?, created_time=?, record_count=?, trailer_total=?, status=? WHERE id=?",
            fileType, headerText, createdDate, createdTime, dataTotal, trailerTotal, status, fileId);

        // ─── PROMOTION into ref_bin_range (business-confirmed 2026-08-09) ───
        //   T068 = full replacement: DELETE all MASTERCARD rows, insert every
        //          active IP0040T1 record from this file.
        //   T067 = delta: 'A' upserts by (range_low, GCMS product) — the
        //          record-generating key per the manual — 'I' removes that
        //          key. No mass delete.
        //   T167/T168 (test) = stage only, never touch reference data.
        Map<String, Object> promotion = null;
        String ip40Sub = tableToSub.get("IP0040T1");
        if (ip40Sub != null && ("T067".equals(fileType) || "T068".equals(fileType))) {
            promotion = promoteMcRanges(fileId, name, ip40Sub, "T068".equals(fileType));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("format", "MC_MPE_" + fileType);
        out.put("mode", "T068".equals(fileType) ? "REPLACE" : "T067".equals(fileType) ? "DELTA" : "STAGE");
        if (promotion != null) out.put("mastercardRanges", promotion);
        out.put("mpeFileId", fileId);
        out.put("headerDate", createdDate);
        out.put("loaded", total - unknown);
        out.put("rejected", unknown);
        out.put("status", status);
        out.put("tables", dirRows.stream().map(d -> {
            String sub = tableToSub.getOrDefault((String) d[1], (String) d[3]);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", d[1]); t.put("name", d[2]); t.put("subId", sub); t.put("recLen", d[7]);
            t.put("staged", "IP0000T1".equals(d[1]) ? dirRows.size() : stagedBySub.getOrDefault(sub, 0));
            t.put("declared", declaredByTable.get((String) d[1]));
            return t;
        }).toList());
        if (!problems.isEmpty()) out.put("rejectSamples", problems);
        if (promotion == null) {
            out.put("note", "Staged and validated only — test deliveries (T167/T168) never touch reference data.");
        }
        return out;
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
        // ISO numeric -> alpha-2 (MC gives numeric reliably; alpha is 3-char)
        Map<String, String> numToAlpha2 = new HashMap<>();
        jdbcTemplate.query("SELECT iso_numeric, country_code FROM ref_country WHERE iso_numeric IS NOT NULL",
            rs -> { numToAlpha2.put(rs.getString(1).trim(), rs.getString(2)); });

        int inserted = 0, updated = 0, deleted = 0, quarantined = 0;
        if (fullReplace) {
            jdbcTemplate.update("DELETE FROM ref_bin_range WHERE scheme = 'MASTERCARD'");
        }
        final String insSql =
            "INSERT INTO ref_bin_range (scheme, range_low, range_high, bin6, region_code, issuer_country, " +
            "product_code, funding_source, card_type, source_file) VALUES ('MASTERCARD',?,?,?,?,?,?,NULL,?,?)";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT active_flag, record_text FROM mpe_record WHERE file_id = ? AND sub_id = ?", fileId, subId);
        List<Object[]> batch = new ArrayList<>();
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

            if ("I".equals(flag)) {
                deleted += jdbcTemplate.update(
                    "DELETE FROM ref_bin_range WHERE scheme='MASTERCARD' AND range_low=? AND COALESCE(product_code,'')=?",
                    low, gcms);
                continue;
            }
            if (fullReplace) {
                batch.add(new Object[]{ low, high, bin6, region.isEmpty() ? null : region,
                    alpha2, gcms, mcCardType(gcms), sourceFile });
                if (batch.size() >= 5000) { jdbcTemplate.batchUpdate(insSql, batch); inserted += batch.size(); batch.clear(); }
            } else {
                int del = jdbcTemplate.update(
                    "DELETE FROM ref_bin_range WHERE scheme='MASTERCARD' AND range_low=? AND COALESCE(product_code,'')=?",
                    low, gcms);
                jdbcTemplate.update(insSql, low, high, bin6, region.isEmpty() ? null : region,
                    alpha2, gcms, mcCardType(gcms), sourceFile);
                if (del > 0) updated++; else inserted++;
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
    private static final Set<String> MC_CREDIT = Set.of(
        "MCC", "MCS", "MCG", "MPL", "MCB", "MCO", "MCW", "MWE", "MEB",
        "MAB", "MRG", "MRW", "MBK", "MPB", "MCF", "MRJ", "MRK");

    private static String mcCardType(String gcms) {
        if (gcms.startsWith("MD") || "CIR".equals(gcms)) return "DEBIT";
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
        List<Object[]> batch = new ArrayList<>();
        List<String> rejects = new ArrayList<>();
        Map<String, Integer> byCountry = new HashMap<>();
        int lineNo = 0, loaded = 0, rejected = 0;

        boolean replace = !"APPEND".equalsIgnoreCase(mode);
        if (replace) {
            jdbcTemplate.update("DELETE FROM ref_bin_range WHERE scheme = 'VISA'");
        }

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
        out.put("mode", replace ? "REPLACE" : "APPEND");
        out.put("loaded", loaded);
        out.put("rejected", rejected);
        if (!rejects.isEmpty()) out.put("rejectSamples", rejects);
        // Validation summary: reject-rate and country sanity are the drift alarms.
        out.put("bahrainRanges", byCountry.getOrDefault("BH", 0));
        out.put("distinctCountries", byCountry.size());
        out.put("totalRanges", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ref_bin_range WHERE scheme='VISA'", Long.class));
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
