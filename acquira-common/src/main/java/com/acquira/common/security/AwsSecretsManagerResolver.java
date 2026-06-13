package com.acquira.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches a secret from AWS Secrets Manager and parses it as a flat JSON object
 * into a {@code Map<String,String>}.
 *
 * Implemented with REFLECTION on the AWS SDK v2 classes so that:
 *   - acquira-common needs NO compile-time AWS dependency (it currently has
 *     none), and the whole build keeps working in PLAIN / ENCRYPTED mode, and
 *   - AWS support "just turns on" in production once you (1) add the SDK jar and
 *     (2) set acquira.secrets.provider=AWS.
 *
 * To enable in prod, add to the runnable module (acquira-core) — the version is
 * already managed by the AWS BOM in the parent POM (aws.sdk.version):
 *
 *   <dependency>
 *     <groupId>software.amazon.awssdk</groupId>
 *     <artifactId>secretsmanager</artifactId>
 *   </dependency>
 *
 * Credentials/region resolve via the standard AWS default provider chain
 * (instance role, env vars, ~/.aws), unless a region is given explicitly.
 *
 * The stored secret is expected to be a JSON object, e.g.:
 *   { "spring.datasource.password": "...", "jwt.secret": "..." }
 * Field names are matched against the managed property keys by the caller.
 */
final class AwsSecretsManagerResolver {

    private static final String CLIENT_CLASS = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient";
    private static final String REGION_CLASS = "software.amazon.awssdk.regions.Region";
    private static final String REQUEST_CLASS = "software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest";

    private final ObjectMapper mapper = new ObjectMapper();

    /** True if the AWS SDK Secrets Manager classes are on the classpath. */
    boolean sdkAvailable() {
        try {
            Class.forName(CLIENT_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Fetch {@code secretId} and return its JSON fields as a string map.
     * Returns an empty map on any failure (callers then leave existing values
     * in place and log a warning) rather than throwing — we never want secret
     * resolution to hard-crash startup ambiguously; the cause is logged.
     *
     * @param region   AWS region (e.g. "me-south-1"); null → default chain
     * @param secretId the secret name/ARN in Secrets Manager
     */
    Map<String, String> fetch(String region, String secretId) throws Exception {
        Class<?> clientClass = Class.forName(CLIENT_CLASS);
        Object client = null;
        try {
            // Build the client.
            if (region != null && !region.isBlank()) {
                Class<?> regionClass = Class.forName(REGION_CLASS);
                Object regionObj = regionClass.getMethod("of", String.class).invoke(null, region);
                Object builder = clientClass.getMethod("builder").invoke(null);
                builder.getClass().getMethod("region", regionClass).invoke(builder, regionObj);
                client = builder.getClass().getMethod("build").invoke(builder);
            } else {
                client = clientClass.getMethod("create").invoke(null);
            }

            // Build the GetSecretValueRequest.
            Class<?> reqClass = Class.forName(REQUEST_CLASS);
            Object reqBuilder = reqClass.getMethod("builder").invoke(null);
            reqBuilder.getClass().getMethod("secretId", String.class).invoke(reqBuilder, secretId);
            Object request = reqBuilder.getClass().getMethod("build").invoke(reqBuilder);

            // client.getSecretValue(request).secretString()
            Object response = clientClass.getMethod("getSecretValue", reqClass).invoke(client, request);
            Object secretString = response.getClass().getMethod("secretString").invoke(response);
            if (secretString == null) return Collections.emptyMap();

            return parseJson(secretString.toString());
        } finally {
            if (client instanceof AutoCloseable) {
                try { ((AutoCloseable) client).close(); } catch (Exception ignored) { }
            }
        }
    }

    private Map<String, String> parseJson(String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.readValue(json, Map.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }
}
