package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.core.service.S3FileService;
import com.acquira.core.service.S3FileService.S3Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3 file maintenance — the second tab of the S3 screen: browse, upload,
 * download and delete the objects under the tenant's configured prefix.
 *
 * <p>Config lives next door in {@link S3SettingsController}; this controller
 * never reads or writes credentials, it only consumes the resolved config.
 * Everything is scoped to the configured prefix by {@link S3FileService}.
 *
 * <p>Gated on the same screen as the settings tab, so a user who cannot see
 * S3 Report Storage cannot reach the delete endpoint behind it either.
 */
@RestController
@RequestMapping("/api/admin/s3-files")
@PreAuthorize("@menuAccess.canAccess('/admin/s3-settings')")
@RequiredArgsConstructor
@Slf4j
public class S3FileController {

    /** Above this, a multi-file upload is refused rather than half-applied. */
    private static final int  MAX_FILES_PER_UPLOAD = 25;
    private static final long MAX_UPLOAD_BYTES     = 200L * 1024 * 1024;   // 200 MB per file

    private final S3FileService s3Files;

    /** ── LIST one folder under the configured prefix ── */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(required = false) String token) {
        try {
            S3Config cfg = config();
            return ResponseEntity.ok(s3Files.list(cfg, path, token));
        } catch (IllegalStateException e) {
            return notConfigured(e);
        } catch (SecurityException e) {
            return problem(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.warn("[S3Files] List failed for path '{}': {}", path, e.getMessage());
            return problem(HttpStatus.BAD_GATEWAY, describe(e));
        }
    }

    /** ── UPLOAD one or more files into a folder under the configured prefix ── */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(defaultValue = "") String path) {

        if (files == null || files.length == 0) {
            return problem(HttpStatus.BAD_REQUEST, "No files were supplied");
        }
        if (files.length > MAX_FILES_PER_UPLOAD) {
            return problem(HttpStatus.BAD_REQUEST,
                "Too many files in one upload (max " + MAX_FILES_PER_UPLOAD + ")");
        }
        for (MultipartFile f : files) {
            if (f.getSize() > MAX_UPLOAD_BYTES) {
                return problem(HttpStatus.PAYLOAD_TOO_LARGE,
                    f.getOriginalFilename() + " is larger than the 200 MB per-file limit");
            }
        }

        S3Config cfg;
        try {
            cfg = config();
        } catch (IllegalStateException e) {
            return notConfigured(e);
        }

        // Per-file outcomes: one bad file must not discard the ones that landed.
        List<String>              uploaded = new ArrayList<>();
        List<Map<String, String>> failed   = new ArrayList<>();

        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            try {
                String key = s3Files.upload(cfg, path, name, f.getBytes(), f.getContentType());
                uploaded.add(key);
            } catch (Exception e) {
                log.warn("[S3Files] Upload of '{}' failed: {}", name, e.getMessage());
                failed.add(Map.of("name", String.valueOf(name), "message", describe(e)));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploaded", uploaded);
        body.put("failed",   failed);
        return ResponseEntity.ok(body);
    }

    /** ── CREATE a folder (body: { path, name }) ── */
    @PostMapping("/folder")
    public ResponseEntity<Map<String, Object>> createFolder(@RequestBody Map<String, Object> body) {
        try {
            String key = s3Files.createFolder(config(), str(body, "path"), str(body, "name"));
            return ResponseEntity.ok(Map.of("key", key));
        } catch (IllegalStateException e) {
            return notConfigured(e);
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            return problem(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.warn("[S3Files] Create folder failed: {}", e.getMessage());
            return problem(HttpStatus.BAD_GATEWAY, describe(e));
        }
    }

    /**
     * ── RENAME a file or a folder (body: { key, newName }) ──
     *
     * A key ending in '/' is a folder, which is copy-every-object-then-delete —
     * see S3FileService#renameFolder for why that is capped and non-atomic.
     */
    @PostMapping("/rename")
    public ResponseEntity<Map<String, Object>> rename(@RequestBody Map<String, Object> body) {
        String key     = str(body, "key");
        String newName = str(body, "newName");
        try {
            S3Config cfg = config();
            if (key.endsWith("/")) {
                return ResponseEntity.ok(s3Files.renameFolder(cfg, key, newName));
            }
            return ResponseEntity.ok(Map.of("key", s3Files.renameObject(cfg, key, newName)));
        } catch (IllegalStateException e) {
            return notConfigured(e);
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            log.warn("[S3Files] Refused rename outside the configured prefix: {}", e.getMessage());
            return problem(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.warn("[S3Files] Rename of '{}' failed: {}", key, e.getMessage());
            return problem(HttpStatus.BAD_GATEWAY, describe(e));
        }
    }

    /** ── DELETE one object ── */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteOne(@RequestParam String key) {
        return runDelete(List.of(key));
    }

    /** ── DELETE many objects (body: { keys: [...] }) ── */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteMany(@RequestBody Map<String, Object> body) {
        Object raw = body.get("keys");
        if (!(raw instanceof List<?> list)) {
            return problem(HttpStatus.BAD_REQUEST, "Expected a 'keys' array");
        }
        return runDelete(list.stream().map(String::valueOf).toList());
    }

    /** ── DOWNLOAD one object ── */
    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam String key) {
        try {
            byte[] bytes = s3Files.download(config(), key);
            String filename = key.substring(key.lastIndexOf('/') + 1);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
        } catch (IllegalStateException e) {
            return notConfigured(e);
        } catch (SecurityException e) {
            return problem(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.warn("[S3Files] Download of '{}' failed: {}", key, e.getMessage());
            return problem(HttpStatus.BAD_GATEWAY, describe(e));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private S3Config config() {
        return s3Files.config(TenantContext.getCurrentTenant());
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : v.toString();
    }

    private ResponseEntity<Map<String, Object>> runDelete(List<String> keys) {
        try {
            Map<String, Object> result = s3Files.delete(config(), keys);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return notConfigured(e);
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            log.warn("[S3Files] Refused delete outside the configured prefix: {}", e.getMessage());
            return problem(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.warn("[S3Files] Delete failed: {}", e.getMessage());
            return problem(HttpStatus.BAD_GATEWAY, describe(e));
        }
    }

    /**
     * 409 rather than 500: nothing is broken, the tenant simply has not finished
     * the config tab. The UI keys off this status to point the operator there.
     */
    private static ResponseEntity<Map<String, Object>> notConfigured(Exception e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "error",   true,
            "message", message == null ? status.getReasonPhrase() : message));
    }

    /** Keep AWS exception text useful without leaking the stack trace to the browser. */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        if (msg.contains("AccessDenied")) {
            return "Access denied — the IAM user needs s3:ListBucket, s3:GetObject, "
                 + "s3:PutObject and s3:DeleteObject on this bucket";
        }
        if (msg.contains("NoSuchBucket")) return "The configured bucket does not exist";
        // CopyObject's own wording for this is opaque; renames are the only path here that copies.
        if (msg.contains("larger than the maximum allowable size")) {
            return "Objects larger than 5 GB cannot be renamed — S3 renames by copying";
        }
        if (msg.contains("InvalidAccessKeyId")) return "Invalid access key ID";
        if (msg.contains("SignatureDoesNotMatch")) return "Invalid secret access key";
        return msg;
    }
}
