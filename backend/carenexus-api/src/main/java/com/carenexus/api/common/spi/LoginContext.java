package com.carenexus.api.common.spi;

/**
 * Request context of a login attempt, passed to {@link LoginGate}s and to {@code AuthService.completeLogin}.
 *
 * @param ip         client address ({@code request.getRemoteAddr()}; proxy headers are resolved by the container)
 * @param userAgent  User-Agent header, may be null
 * @param deviceType client-declared device type (for example WEB, IOS, ANDROID), may be null
 * @param deviceInfo client-declared device description, may be null
 */
public record LoginContext(String ip, String userAgent, String deviceType, String deviceInfo) {
}
