package com.carenexus.api.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value must not contain something that looks like a payment card number: 13 to 19 digits, optionally grouped
 * with short separators (one or two spaces, hyphens, dots or slashes), that pass the Luhn check, also when more digits
 * follow (a card number typed with its expiry). Card numbers are never stored (PCI scope); a user who types one gets
 * a validation error instead. {@code null} is valid. See {@link NoCardNumberValidator} for the exact rule.
 */
@Documented
@Constraint(validatedBy = NoCardNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoCardNumber {

    String MESSAGE = "must not contain a card number";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * For identifier fields (policy or member numbers) where long digit strings are normal: only a Luhn-valid 13 to
     * 19 digit number that starts at a digit group and begins with a card network prefix (Visa, Mastercard, American
     * Express, Discover, JCB, UnionPay) is refused.
     */
    boolean requireCardNetworkPrefix() default false;
}
