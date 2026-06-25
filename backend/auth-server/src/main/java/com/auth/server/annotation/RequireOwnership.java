package com.auth.server.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireOwnership {
    /**
     * Parameter name containing the owner ID
     * e.g., "userId", "patientId", "ownerId"
     */
    String ownerIdParam() default "userId";

    /**
     * Resource type for audit logging
     */
    String resourceType() default "";

    /**
     * Parameter name containing the resource ID
     */
    String resourceIdParam() default "";
}
