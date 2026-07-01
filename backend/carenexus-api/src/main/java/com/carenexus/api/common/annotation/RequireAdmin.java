package com.carenexus.api.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
    /**
     * Whether FACILITY_ADMIN is also allowed
     */
    boolean allowFacilityAdmin() default false;
}
