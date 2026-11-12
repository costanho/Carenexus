package com.carenexus.api.delivery.email;

import com.carenexus.api.delivery.Redaction;

/**
 * One email handed to an {@link EmailSender}.
 *
 * @param to         recipient address
 * @param subject    rendered subject line
 * @param textBody   rendered plain-text body
 * @param templateId template the text was rendered from, or null
 * @param messageKey stable key of the outbox row (its id); the same for every attempt, so a provider that supports
 *                   idempotency keys can drop a repeated send
 */
public record EmailMessage(String to, String subject, String textBody, String templateId, String messageKey) {

    /** Never prints the text; the address is masked. */
    @Override
    public String toString() {
        return "EmailMessage[to=" + Redaction.maskEmail(to) + ", templateId=" + templateId
                + ", messageKey=" + messageKey + "]";
    }
}
