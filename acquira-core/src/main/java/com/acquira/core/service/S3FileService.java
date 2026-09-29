package com.acquira.core.service;

import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.TenantSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browse / upload / delete for the objects under a tenant's configured S3
 * report prefix — the maintenance side of the S3 screen, as opposed to
 * {@link ReportS3UploadService}, which only ever writes what the batch produced.
 *
 * <p>Two rules hold for every operation here, because this is the one place in
 * the product where an operator can destroy archived reports by hand:
 *
 * <ol>
 *   <li><b>The configured prefix is a hard boundary.</b> Every key is resolved
 *       against it and re-checked afterwards, so a crafted {@code path} or
 *       {@code key} (a {@code ..} segment, an absolute key, a sibling prefix
 *       that merely starts with the same characters) cannot read or delete
 *       outside the tenant's own folder. The bucket may well hold other
 *       tenants' reports.</li>
 *   <li><b>Credentials are resolved per call</b> from tenant_setting and the
 *       S3Client is closed with the call, matching S3EncryptionService. No
 *       client is cached, so a credential rotation takes effect immediately and
 *       one tenant's client can never serve another's request.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S3FileService {

    private static final String KEY_ENABLED    = "s3.enabled";
    private static final String KEY_REGION     = "s3.region";
    private static final String KEY_BUCKET     = "s3.bucket";
    private static final String KEY_PREFIX     = "s3.prefix";
    private static final String KEY_ACCESS_KEY = "s3.accessKeyId";
    private static final String KEY_SECRET_KEY = "s3.secretAccessKey";

    /** One page of a listing. Deliberately finite — a report bucket can hold millions of keys. */
    private static final int MAX_KEYS = 1000;

    /**
     * Ceiling on a folder rename. S3 has no rename, so renaming a prefix is
     * copy-every-object-then-delete-every-object, and it is not atomic: an
     * interruption halfway leaves objects under both names. Rather than let an
     * operator start that on a prefix holding a year of reports, anything above
     * this is refused outright with an explanation.
     */
    private static final int MAX_RENAME_OBJECTS = 1000;

    private final TenantSettingRepository settingRepo;
    private final S3EncryptionService     encryptionService;

    /** Resolved per-tenant S3 connection details, secret already decrypted. */
    public record S3Config(boolean enabled, String region, String bucket, String prefix,
                           String accessKeyId, String secretAccessKey) {

        /** The configured prefix, normalised to either "" or "something/". */
        String base() {
            if (prefix == null || prefix.isBlank()) return "";
            String p = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
            return p.isEmpty() ? "" : p + "/";
        }
    }

    // ── config ──────────────────────────────────────────────────────────────

    /**
     * Resolve the tenant's S3 settings.
     *
     * @throws IllegalStateException when S3 is off or the credentials are incomplete —
     *         the controller turns this into a 409 so the UI can point at the config tab.
     */
    public S3Config config(Long tenantId) {
        if (tenantId == null) throw new IllegalStateException("No tenant in context");

        if (!getBool(tenantId, KEY_ENABLED, false)) {
            throw new IllegalStateException("S3 storage is disabled for this tenant");
        }

        String region    = getString(tenantId, KEY_REGION,     "me-south-1");
        String bucket    = getString(tenantId, KEY_BUCKET,     "");
        String prefix    = getString(tenantId, KEY_PREFIX,     "reports");
        String accessKey = getString(tenantId, KEY_ACCESS_KEY, "");
        String encrypted = getString(tenantId, KEY_SECRET_KEY, "");

        if (bucket.isBlank() || accessKey.isBlank() || encrypted.isBlank()) {
            throw new IllegalStateException("S3 bucket or credentials are not configured");
        }

        return new S3Config(true, region, bucket, prefix, accessKey,
                            encryptionService.decrypt(encrypted));
    }

    // ── list ────────────────────────────────────────────────────────────────

    /**
     * One folder's worth of listing: the immediate sub-folders and the objects
     * directly inside {@code relativePath}, which is interpreted relative to the
     * configured prefix ("" is the prefix root).
     */
    public Map<String, Object> list(S3Config cfg, String relativePath, String continuationToken) {
        String base   = cfg.base();
        String folder = resolveFolder(cfg, relativePath);

        try (S3Client s3 = client(cfg)) {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                .bucket(cfg.bucket())
                .prefix(folder)
                .delimiter("/")
                .maxKeys(MAX_KEYS);
            if (continuationToken != null && !continuationToken.isBlank()) {
                req.continuationToken(continuationToken);
            }

            ListObjectsV2Response res = s3.listObjectsV2(req.build());

            List<Map<String, Object>> folders = new ArrayList<>();
            for (CommonPrefix cp : res.commonPrefixes()) {
                folders.add(Map.of(
                    "key",  cp.prefix(),
                    "path", cp.prefix().substring(base.length()),
                    "name", trimSlashes(cp.prefix().substring(folder.length()))
                ));
            }

            List<Map<String, Object>> files = new ArrayList<>();
            for (S3Object o : res.contents()) {
                // A "folder/" placeholder object is the folder itself, not a file in it.
                if (o.key().equals(folder)) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("key",          o.key());
                f.put("name",         o.key().substring(folder.length()));
                f.put("size",         o.size());
                f.put("lastModified", o.lastModified() == null ? null : o.lastModified().toString());
                f.put("storageClass", o.storageClassAsString());
                files.add(f);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket",     cfg.bucket());
            out.put("region",     cfg.region());
            out.put("basePrefix", base);
            out.put("path",       folder.substring(base.length()));
            out.put("folders",    folders);
            out.put("files",      files);
            out.put("truncated",  Boolean.TRUE.equals(res.isTruncated()));
            out.put("nextToken",  res.nextContinuationToken());
            return out;
        }
    }

    // ── upload ──────────────────────────────────────────────────────────────

    /** Upload one file into {@code relativePath} under the configured prefix. Returns the key written. */
    public String upload(S3Config cfg, String relativePath, String filename,
                         byte[] content, String contentType) {
        String key = resolveFolder(cfg, relativePath) + sanitiseFilename(filename);
        assertInsideBase(cfg, key);

        try (S3Client s3 = client(cfg)) {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(cfg.bucket())
                    .key(key)
                    .contentType(contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType)
                    .build(),
                RequestBody.fromBytes(content));
        }
        log.info("[S3Files] Uploaded {} bytes to s3://{}/{}", content.length, cfg.bucket(), key);
        return key;
    }

    // ── create folder ───────────────────────────────────────────────────────

    /**
     * Create a folder under {@code relativePath} by writing the zero-byte
     * {@code name/} placeholder object that every S3 console uses for the
     * purpose. S3 has no directories; the placeholder exists only so the new
     * folder is visible here before anything has been uploaded into it, and the
     * listing filters it back out.
     *
     * @return the placeholder key written
     */
    public String createFolder(S3Config cfg, String relativePath, String name) {
        String folder = resolveFolder(cfg, relativePath);
        String key    = folder + sanitiseSegment(name) + "/";
        assertInsideBase(cfg, key);

        try (S3Client s3 = client(cfg)) {
            if (exists(s3, cfg, key)) {
                throw new IllegalArgumentException("A folder named '" + name + "' already exists here");
            }
            s3.putObject(
                PutObjectRequest.builder().bucket(cfg.bucket()).key(key).build(),
                RequestBody.empty());
        }
        log.info("[S3Files] Created folder s3://{}/{}", cfg.bucket(), key);
        return key;
    }

    // ── rename ──────────────────────────────────────────────────────────────

    /**
     * Rename one object, keeping it in its current folder.
     *
     * <p>Copy-then-delete, because S3 has no rename. The copy is verified before
     * the original is deleted, so a failed copy leaves the original untouched
     * rather than losing the object.
     *
     * @return the new key
     */
    public String renameObject(S3Config cfg, String key, String newName) {
        assertInsideBase(cfg, key);
        if (key.endsWith("/")) {
            throw new IllegalArgumentException("Use the folder rename for a prefix");
        }

        String folder = key.substring(0, key.lastIndexOf('/') + 1);
        String target = folder + sanitiseSegment(newName);
        assertInsideBase(cfg, target);

        if (target.equals(key)) return key;

        try (S3Client s3 = client(cfg)) {
            if (exists(s3, cfg, target)) {
                throw new IllegalArgumentException("A file named '" + newName + "' already exists here");
            }
            copy(s3, cfg, key, target);
            s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(cfg.bucket())
                .delete(Delete.builder()
                    .objects(ObjectIdentifier.builder().key(key).build())
                    .build())
                .build());
        }
        log.info("[S3Files] Renamed s3://{}/{} -> {}", cfg.bucket(), key, target);
        return target;
    }

    /**
     * Rename a folder — copy every object under the old prefix to the new one,
     * then delete the originals.
     *
     * <p>Not atomic, and refused above {@link #MAX_RENAME_OBJECTS} objects. All
     * copies are done before any delete, so an interruption leaves the objects
     * present under the old name (duplicated at worst) rather than missing.
     *
     * @return a summary: the new prefix and how many objects moved
     */
    public Map<String, Object> renameFolder(S3Config cfg, String folderKey, String newName) {
        assertInsideBase(cfg, folderKey);
        String source = folderKey.endsWith("/") ? folderKey : folderKey + "/";

        // Strip the trailing slash before taking the parent, or lastIndexOf finds
        // the folder's own separator and the "parent" is the folder itself.
        String withoutSlash = source.substring(0, source.length() - 1);
        String parent = withoutSlash.substring(0, withoutSlash.lastIndexOf('/') + 1);
        String target = parent + sanitiseSegment(newName) + "/";
        assertInsideBase(cfg, target);

        if (target.equals(source)) {
            return Map.of("prefix", source, "moved", 0);
        }
        if (target.startsWith(source)) {
            throw new IllegalArgumentException("A folder cannot be renamed into itself");
        }

        try (S3Client s3 = client(cfg)) {
            if (!listAllUnder(s3, cfg, target).isEmpty() || exists(s3, cfg, target)) {
                throw new IllegalArgumentException("A folder named '" + newName + "' already exists here");
            }

            List<String> keys = listAllUnder(s3, cfg, source);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("That folder no longer exists");
            }
            if (keys.size() > MAX_RENAME_OBJECTS) {
                throw new IllegalArgumentException(
                    "This folder holds " + keys.size() + " objects. Renaming copies every one of them and "
                  + "is not atomic, so it is capped at " + MAX_RENAME_OBJECTS
                  + " — rename the sub-folders individually instead.");
            }

            // Every copy first; only then the deletes.
            for (String k : keys) {
                copy(s3, cfg, k, target + k.substring(source.length()));
            }
            for (int i = 0; i < keys.size(); i += MAX_KEYS) {
                List<ObjectIdentifier> batch = keys.subList(i, Math.min(i + MAX_KEYS, keys.size()))
                    .stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(cfg.bucket())
                    .delete(Delete.builder().objects(batch).build())
                    .build());
            }

            log.info("[S3Files] Renamed folder s3://{}/{} -> {} ({} objects)",
                cfg.bucket(), source, target, keys.size());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("prefix", target);
            out.put("path",   target.substring(cfg.base().length()));
            out.put("moved",  keys.size());
            return out;
        }
    }

    // ── delete ──────────────────────────────────────────────────────────────

    /**
     * Delete the given keys. A key outside the configured prefix fails the whole
     * request rather than being skipped, so a request that tried to reach out of
     * the folder fails visibly instead of half-succeeding.
     */
    public Map<String, Object> delete(S3Config cfg, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("No keys supplied");
        }
        if (keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException("Too many keys in one request (max " + MAX_KEYS + ")");
        }
        for (String k : keys) assertInsideBase(cfg, k);

        List<ObjectIdentifier> ids = keys.stream()
            .map(k -> ObjectIdentifier.builder().key(k).build())
            .toList();

        try (S3Client s3 = client(cfg)) {
            DeleteObjectsResponse res = s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(cfg.bucket())
                .delete(Delete.builder().objects(ids).quiet(false).build())
                .build());

            List<String> deleted = res.deleted().stream().map(DeletedObject::key).toList();
            List<Map<String, String>> errors = res.errors().stream()
                .map(e -> Map.of("key",     String.valueOf(e.key()),
                                 "message", String.valueOf(e.message())))
                .toList();

            log.info("[S3Files] Deleted {} object(s) from s3://{} ({} error(s))",
                deleted.size(), cfg.bucket(), errors.size());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", deleted);
            out.put("errors",  errors);
            return out;
        }
    }

    // ── download ────────────────────────────────────────────────────────────

    /** Fetch one object's bytes for a browser download. */
    public byte[] download(S3Config cfg, String key) {
        assertInsideBase(cfg, key);
        try (S3Client s3 = client(cfg)) {
            ResponseBytes<?> bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(cfg.bucket()).key(key).build());
            return bytes.asByteArray();
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** True when exactly this key exists. Uses a 1-key listing so a 404 is not an exception. */
    private boolean exists(S3Client s3, S3Config cfg, String key) {
        ListObjectsV2Response res = s3.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(cfg.bucket()).prefix(key).maxKeys(1).build());
        return res.contents().stream().anyMatch(o -> o.key().equals(key));
    }

    /** Every object key under a prefix, following pagination, capped one page past the rename limit. */
    private List<String> listAllUnder(S3Client s3, S3Config cfg, String prefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response res = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(cfg.bucket()).prefix(prefix).maxKeys(MAX_KEYS)
                .continuationToken(token).build());
            res.contents().forEach(o -> keys.add(o.key()));
            token = Boolean.TRUE.equals(res.isTruncated()) ? res.nextContinuationToken() : null;
            // One page past the cap is enough to report the real count is over it.
        } while (token != null && keys.size() <= MAX_RENAME_OBJECTS);
        return keys;
    }

    private void copy(S3Client s3, S3Config cfg, String from, String to) {
        s3.copyObject(CopyObjectRequest.builder()
            .sourceBucket(cfg.bucket()).sourceKey(from)
            .destinationBucket(cfg.bucket()).destinationKey(to)
            .build());
    }

    private S3Client client(S3Config cfg) {
        return S3Client.builder()
            .region(Region.of(cfg.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(cfg.accessKeyId(), cfg.secretAccessKey())))
            .build();
    }

    /**
     * Turn a UI-supplied relative path into a full S3 folder prefix ending in "/".
     * Traversal segments are dropped rather than rejected, so navigating with a
     * stale breadcrumb lands somewhere harmless instead of erroring.
     */
    private String resolveFolder(S3Config cfg, String relativePath) {
        String base = cfg.base();
        if (relativePath == null || relativePath.isBlank()) return base;

        StringBuilder sb = new StringBuilder(base);
        for (String seg : relativePath.split("/")) {
            String s = seg.trim();
            if (s.isEmpty() || s.equals(".") || s.equals("..")) continue;
            sb.append(s).append('/');
        }
        String folder = sb.toString();
        assertInsideBase(cfg, folder);
        return folder;
    }

    /** The boundary check. Compares against the normalised base, which always ends in "/". */
    private void assertInsideBase(S3Config cfg, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key is required");
        }
        if (key.contains("..") || key.startsWith("/")) {
            throw new SecurityException("Key is outside the configured S3 prefix: " + key);
        }
        // An empty base means the whole bucket is the configured area; otherwise the
        // key must sit under "base/" — not merely start with the base's characters.
        String base = cfg.base();
        if (!base.isEmpty() && !key.startsWith(base)) {
            throw new SecurityException("Key is outside the configured S3 prefix: " + key);
        }
    }

    /** An upload names the object, never a path — a filename may not carry directories. */
    private static String sanitiseFilename(String filename) {
        String name = filename == null ? "" : filename.replaceFirst("^.*[/\\\\]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("A file name is required");
        }
        return name;
    }

    /**
     * A single path segment for a new folder or a rename target. Unlike an
     * upload's filename, a separator here is rejected rather than stripped: the
     * operator typed it, and silently turning "a/b" into "b" would create a
     * folder somewhere they did not mean.
     */
    private static String sanitiseSegment(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A name is required");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("A name cannot contain '/' — create or open one folder at a time");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("'" + name + "' is not a usable name");
        }
        if (name.length() > 200) {
            throw new IllegalArgumentException("That name is too long (max 200 characters)");
        }
        return name;
    }

    private static String trimSlashes(String s) {
        return s.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String getString(Long tenantId, String key, String def) {
        return settingRepo.findByTenant_TenantIdAndKey(tenantId, key)
            .map(TenantSetting::getValue).orElse(def);
    }

    private boolean getBool(Long tenantId, String key, boolean def) {
        return settingRepo.findByTenant_TenantIdAndKey(tenantId, key)
            .map(s -> Boolean.parseBoolean(s.getValue())).orElse(def);
    }
}
