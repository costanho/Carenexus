package com.carenexus.api.delivery;

/**
 * What a sender returns when the provider accepted a message.
 *
 * @param providerMessageId opaque id assigned by the provider (may be null when the provider gives none)
 */
public record SendResult(String providerMessageId) {
}
