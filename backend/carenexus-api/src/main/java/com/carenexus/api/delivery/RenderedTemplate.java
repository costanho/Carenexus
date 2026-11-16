package com.carenexus.api.delivery;

/**
 * A message template with its placeholders filled in.
 *
 * @param subject the {@code Subject:} line (used for EMAIL, ignored for SMS)
 * @param body    the plain-text body
 */
public record RenderedTemplate(String subject, String body) {

    /** Never prints the rendered text (it can hold one-time links). */
    @Override
    public String toString() {
        return "RenderedTemplate[bodyLength=" + (body == null ? 0 : body.length()) + "]";
    }
}
