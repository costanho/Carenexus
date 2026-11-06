package com.carenexus.api.common.annotation;

import java.lang.annotation.*;

/**
 * The caller must own the resource: the value of the parameter named {@link #ownerIdParam()} (normally a String
 * systemId such as "patientSystemId") must equal the caller's systemId. No admin, doctor or caregiver bypass.
 * For patient data that doctors/caregivers may also read, call AuthorizationService.requirePatientAccess instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireOwnership {
    /**
     * Parameter name containing the owner's systemId (String), e.g. "patientSystemId".
     * A legacy Integer "userId" parameter is compared with the caller's userId.
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
