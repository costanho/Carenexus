package com.carenexus.api.common.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Names and helpers shared by the request-correlation filters.
 *
 * <ul>
 *   <li>{@link #HEADER} ({@code X-Request-Id}): accepted from the caller when well formed, otherwise generated, and
 *       always echoed on the response (CORS exposes it, SecurityConfig).</li>
 *   <li>MDC keys {@link #MDC_REQUEST_ID} and {@link #MDC_SYSTEM_ID}: printed by the log pattern
 *       ({@code logging.pattern.level} in application.yml) so a client report carrying the id finds the server log
 *       lines of that request. The system id is the caller's public account id (for example {@code PAT-00001}),
 *       never an email address or a token.</li>
 *   <li>{@link #currentRequestId()}: the id of the request being handled on this thread, copied into every error
 *       envelope by {@link com.carenexus.api.common.dto.ErrorResponse#of}.</li>
 * </ul>
 */
public final class RequestCorrelation {

    /** Request and response header carrying the correlation id. */
    public static final String HEADER = "X-Request-Id";

    /** MDC key of the correlation id. */
    public static final String MDC_REQUEST_ID = "requestId";

    /** MDC key of the authenticated caller's system id (absent for anonymous requests). */
    public static final String MDC_SYSTEM_ID = "systemId";

    /** Request attribute holding the id, so an error or async dispatch of the same request reuses it. */
    public static final String REQUEST_ATTRIBUTE = RequestCorrelation.class.getName() + ".requestId";

    /** Longest caller-supplied id that is accepted. */
    public static final int MAX_LENGTH = 64;

    /**
     * A caller-supplied id is used only when it is 8 to 64 characters of letters, digits, '.', '_', ':' or '-' and
     * starts with a letter or digit (UUIDs, ULIDs and most tracing ids qualify). Anything else is replaced, so the
     * value can never inject text into a log line or a response header.
     */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7," + (MAX_LENGTH - 1) + "}$");

    private RequestCorrelation() {
    }

    /** True when {@code candidate} may be used as the request id as is. */
    public static boolean isWellFormed(String candidate) {
        return candidate != null && WELL_FORMED.matcher(candidate).matches();
    }

    /** A new random id (UUID). */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** The id of the request being handled on this thread, or null outside a request (jobs, startup). */
    public static String currentRequestId() {
        return MDC.get(MDC_REQUEST_ID);
    }
}
