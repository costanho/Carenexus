package com.carenexus.api.calls.provider;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
