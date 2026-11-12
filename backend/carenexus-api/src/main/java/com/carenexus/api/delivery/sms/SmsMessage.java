package com.carenexus.api.delivery.sms;

import com.carenexus.api.delivery.Redaction;

/**
 * One text message handed to an {@link SmsSender}.
 *
 * @param to         recipient phone number as stored on the account
 * @param text       rendered plain text
 * @param templateId template the text was rendered from, or null
 * @param messageKey stable key of the outbox row (its id); the same for every attempt
 */
public record SmsMessage(String to, String text, String templateId, String messageKey) {

    /** Never prints the text; the number is masked. */
    @Override
    public String toString() {
        return "SmsMessage[to=" + Redaction.maskPhone(to) + ", templateId=" + templateId
                + ", messageKey=" + messageKey + "]";
    }
}
