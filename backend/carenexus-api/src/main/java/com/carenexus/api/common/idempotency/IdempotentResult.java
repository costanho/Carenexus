package com.carenexus.api.common.idempotency;

/**
 * Outcome of {@link IdempotencyService#execute}.
 *
 * @param body     the response body: freshly produced, or the stored body of the first request
 * @param replayed true when the action did NOT run because the same key was already completed; controllers answer
 *                 200 instead of 201 then
 */
public record IdempotentResult<T>(T body, boolean replayed) {
}
