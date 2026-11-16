package com.carenexus.api.billing.provider;

/** The webhook signature is missing, malformed or does not match the body (HTTP 401). */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
