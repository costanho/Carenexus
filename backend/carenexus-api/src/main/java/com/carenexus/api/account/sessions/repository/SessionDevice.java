package com.carenexus.api.account.sessions.repository;

import java.util.UUID;

/**
 * The device a stored session was signed in from, read from its first token.
 *
 * @param sessionId    the session id
 * @param firstTokenId id of the session's first token; a smaller id means an earlier sign-in
 * @param deviceType   device type sent at sign-in, may be null
 * @param deviceInfo   device description sent at sign-in, may be null
 */
public record SessionDevice(UUID sessionId, Integer firstTokenId, String deviceType, String deviceInfo) {
}
