package com.acquira.common.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler (method, or every handler of a class) as a side-effect-free
 * report read whose JSON response may be revalidated by ETag and kept in the
 * frontend's in-memory response cache (frontend/src/api/responseCache.js).
 *
 * {@link ReportResponseEtagAdvice} stamps a weak ETag plus
 * {@code X-Acquira-Cache: report} on 200 responses of marked handlers and
 * answers 304 with no body when the client's If-None-Match already matches.
 * The frontend caches ONLY responses carrying that header, so this annotation
 * is the whole opt-in.
 *
 * Put it ONLY on reads — including POST reads that take a filter body. Never on
 * a handler that writes, sends, triggers or downloads anything: the frontend
 * may answer a repeat of the same request from memory without calling the
 * server. On a class, every handler must qualify; otherwise annotate methods.
 * The returned body must not be mutated after it is returned (cached report
 * payloads already must not be), because the ETag is memoised per instance.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ReportResponse {
}
