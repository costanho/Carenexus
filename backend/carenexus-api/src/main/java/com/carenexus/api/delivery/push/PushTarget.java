package com.carenexus.api.delivery.push;

/**
 * One registered device of the recipient.
 *
 * @param platform {@code IOS}, {@code ANDROID} or {@code WEB}
 * @param token    the push token as registered by the device
 */
public record PushTarget(String platform, String token) {

    /** Never prints the token. */
    @Override
    public String toString() {
        return "PushTarget[platform=" + platform + "]";
    }
}
