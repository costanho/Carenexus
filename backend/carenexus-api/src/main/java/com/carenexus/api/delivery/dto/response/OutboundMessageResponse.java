package com.carenexus.api.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** One outbox row as the development outbox shows it. */
@Schema(description = "One outbound message of the development outbox")
public record OutboundMessageResponse(
        @Schema(description = "Row id", example = "42")
        Integer messageId,

        @Schema(description = "Delivery channel", allowableValues = {"EMAIL", "SMS", "PUSH"}, example = "EMAIL")
        String channel,

        @Schema(description = "System id of the recipient account; null for an address that has no account",
                example = "PAT-00001", nullable = true)
        String recipientSystemId,

        @Schema(description = "Email address (EMAIL) or phone number (SMS) as queued; always null for PUSH",
                example = "john.doe@example.com", nullable = true)
        String recipientAddress,

        @Schema(description = "Template file name without .txt (EMAIL, SMS); null for PUSH",
                example = "account-password-reset", nullable = true)
        String templateId,

        @Schema(description = "EMAIL: rendered subject; PUSH: the title; SMS: null", nullable = true)
        String subject,

        @Schema(description = "Rendered plain-text body (EMAIL, SMS) or the push text. Final as soon as the row exists")
        String body,

        @Schema(description = "PUSH: the data payload; null for EMAIL and SMS", nullable = true)
        Map<String, String> data,

        @Schema(description = "In-app notification this message was fanned out from; null for account mail",
                example = "88", nullable = true)
        Integer notificationId,

        @Schema(description = "The sender's idempotency key, unique across the outbox", example = "PASSWORD_RESET:17",
                nullable = true)
        String dedupeKey,

        @Schema(description = "PENDING, SENT, FAILED or SKIPPED",
                allowableValues = {"PENDING", "SENT", "FAILED", "SKIPPED"}, example = "SENT")
        String status,

        @Schema(description = "Send attempts made so far", example = "1")
        int attempts,

        @Schema(description = "Attempts after which the row becomes FAILED", example = "5")
        int maxAttempts,

        @Schema(description = "When the row is or was due: queue time, a later notBefore, or the next retry time",
                example = "2026-11-10T09:00:00.120Z")
        Instant nextAttemptAt,

        @Schema(description = "Short reason of the last failed attempt or of a skip; never the text or the address",
                example = "Provider disabled", nullable = true)
        String lastError,

        @Schema(description = "Provider id that handled the row; null while the worker has not picked it up",
                example = "local", nullable = true)
        String provider,

        @Schema(description = "Opaque id returned by the provider on success", example = "local-42", nullable = true)
        String providerMessageId,

        @Schema(description = "When the message was queued", example = "2026-11-10T09:00:00.120Z")
        Instant createdAt,

        @Schema(description = "When the row reached SENT or SKIPPED; null for PENDING and FAILED",
                example = "2026-11-10T09:00:03.481Z", nullable = true)
        Instant sentAt) {
}
