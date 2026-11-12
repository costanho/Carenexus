package com.carenexus.api.billing.provider.gateway;

/**
 * The webhook signature was valid but the body is not the gateway's event format, or a required field is missing
 * (HTTP 400). The message never contains any part of the body.
 */
public class MalformedWebhookPayloadException extends RuntimeException {

    public MalformedWebhookPayloadException() {
        super("Malformed webhook payload");
    }
}
