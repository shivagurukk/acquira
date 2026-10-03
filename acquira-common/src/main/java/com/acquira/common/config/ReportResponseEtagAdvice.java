package com.acquira.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * ETag / 304 for {@link ReportResponse} handlers.
 *
 * Report payloads run to several MB, and re-opening a page re-downloaded the
 * whole thing even when the server answered from the report cache. With this
 * advice the frontend sends back the ETag it holds and gets a bodiless 304
 * while the data is unchanged.
 *
 * The ETag is a hash of the serialized body, so it is correct by construction:
 * any change in content (new ingest, other tenant, other user scope, other
 * filter) changes it. Hashing serializes the body once more; to keep repeat
 * opens cheap the hash is memoised per body INSTANCE (weak, identity keys) —
 * cache hits return the same instance from the report cache, so they skip
 * the extra serialization entirely.
 *
 * Works for POST as well as GET: most report endpoints take their filters as
 * a POST body, and the 304 is only ever produced for an explicit If-None-Match
 * sent by our own frontend.
 */
@ControllerAdvice
public class ReportResponseEtagAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ReportResponseEtagAdvice.class);

    /** Header the frontend keys its response cache on. */
    public static final String CACHE_HEADER = "X-Acquira-Cache";

    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** body instance → ETag. Weak keys compare by identity and die with the body. */
    private final Cache<Object, String> etags = Caffeine.newBuilder()
            .weakKeys()
            .maximumSize(4096)
            .build();

    public ReportResponseEtagAdvice(tools.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(ReportResponse.class)
                || returnType.getContainingClass().isAnnotationPresent(ReportResponse.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || contentType == null) return body;
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                && !contentType.getSubtype().endsWith("+json")) return body;
        String method = request.getMethod().name();
        if (!"GET".equals(method) && !"POST".equals(method)) return body;
        // Only plain 200s: a ResponseEntity carrying 4xx/5xx or a 201 is not a
        // cacheable read result.
        if (response instanceof ServletServerHttpResponse sr && sr.getServletResponse().getStatus() != 200) {
            return body;
        }

        String etag = etags.get(body, this::hash);
        if (etag == null) return body;

        response.getHeaders().set("ETag", etag);
        response.getHeaders().set(CACHE_HEADER, "report");
        if (matches(request.getHeaders().getFirst("If-None-Match"), etag)) {
            response.setStatusCode(HttpStatus.NOT_MODIFIED);
            return null; // no body is written for a null advice result
        }
        return body;
    }

    /** Weak ETag over the serialized body; null (no ETag) if it can't be serialized. */
    private String hash(Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return "W/\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (Exception e) {
            log.debug("No ETag for {}: {}", body.getClass().getName(), e.toString());
            return null;
        }
    }

    /** If-None-Match may list several tags and use either weak or strong form. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        String bare = stripWeak(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (stripWeak(c).equals(bare)) return true;
        }
        return false;
    }

    private static String stripWeak(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }
}
