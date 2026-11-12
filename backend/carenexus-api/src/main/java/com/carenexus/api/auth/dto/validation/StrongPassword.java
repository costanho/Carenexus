package com.carenexus.api.auth.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A required password that satisfies {@link PasswordPolicy}. Reports exactly one message per field: "Password is
 * required" for a missing or blank value, otherwise the first rule that fails. Do not combine it with
 * {@code @NotBlank} (that would report a missing password twice).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
public @interface StrongPassword {

    String message() default PasswordPolicy.TOO_WEAK;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
