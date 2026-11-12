package com.carenexus.api.scheduling.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A length in minutes between {@link #MIN} and {@link #MAX} in steps of {@link #STEP} (the appointment duration rule
 * of AppointmentService, also used for the slot length of a template). Null is valid: combine with {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = SlotLength.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SlotLength {

    int MIN = 5;
    int MAX = 240;
    int STEP = 5;
    String MESSAGE = "must be between 5 and 240 in steps of 5";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SlotLength, Integer> {

        /** True when {@code minutes} is an allowed slot or appointment length. */
        public static boolean allowed(int minutes) {
            return minutes >= MIN && minutes <= MAX && minutes % STEP == 0;
        }

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || allowed(value);
        }
    }
}
