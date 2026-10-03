package com.acquira.common.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Loads a component in pod roles beyond the one its module belongs to.
 *
 * {@link PodRoleFilter} loads a class only in the role that owns its module
 * (com.acquira.core -> core, com.acquira.pdf -> pdf, ...). A few classes are
 * needed by another role too — e.g. CrossPodSchemaHealthIndicator lives in core
 * but gates readiness on the pdf and batch pods as well. Annotate those with
 * the extra roles instead of moving them between modules.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoadInRoles {
    /** Additional roles (see {@link PodRoleFilter}) in which this class is loaded. */
    String[] value();
}
