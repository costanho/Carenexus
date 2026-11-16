package com.carenexus.api.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Wrong email or password at login: HTTP 401 with one generic message for both cases, so the response never
 * reveals whether an account exists. GlobalExceptionHandler echoes the message of @ResponseStatus exceptions.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "Invalid email or password";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
