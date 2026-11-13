package com.carenexus.api.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestIdFilter and CallerMdcFilter in isolation: which X-Request-Id values are accepted, the response header,
 * and the MDC values during and after the request.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static String header(MockHttpServletResponse response) {
        return response.getHeader(RequestCorrelation.HEADER);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void aWellFormedRequestId_isEchoed() throws Exception {
        for (String supplied : new String[]{"3f1c9a6e-0b6d-4a4e-9a51-2c1f0d6b7e10", "01HZX3K9Q7W2E5R8T1Y4U6I0OP",
                "web:checkout.17_retry", "abcdefgh"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
            request.addHeader(RequestCorrelation.HEADER, supplied);
            assertEquals(supplied, header(run(request, new MockFilterChain())), supplied);
        }
    }

    @Test
    void surroundingWhitespace_isTrimmed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestCorrelation.HEADER, "  client-request-0001  ");
        assertEquals("client-request-0001", header(run(request, new MockFilterChain())));
    }

    @Test
    void aMissingRequestId_isGenerated() throws Exception {
        String generated = header(run(new MockHttpServletRequest("GET", "/x"), new MockFilterChain()));
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertTrue(RequestCorrelation.isWellFormed(generated));

        String second = header(run(new MockHttpServletRequest("GET", "/x"), new MockFilterChain()));
        assertNotEquals(generated, second, "each request gets its own id");
    }

    @Test
    void aMalformedRequestId_isReplaced() throws Exception {
        for (String bad : new String[]{"", "   ", "short", "has space inside", "line\r\nX-Injected: 1",
                "-starts-with-dash", "quote\"inside1", "x".repeat(RequestCorrelation.MAX_LENGTH + 1), "ünïcode-id-01"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.addHeader(RequestCorrelation.HEADER, bad);
            String echoed = header(run(request, new MockFilterChain()));
            assertNotEquals(bad, echoed, "'" + bad + "' must not be echoed");
            assertDoesNotThrow(() -> UUID.fromString(echoed), "'" + bad + "' is replaced by a generated id");
        }
        assertTrue(RequestCorrelation.isWellFormed("x".repeat(RequestCorrelation.MAX_LENGTH)), "64 characters are accepted");
    }

    @Test
    void theIdIsInTheMdcDuringTheRequest_andRemovedAfterwards() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestCorrelation.HEADER, "mdc-check-0001");

        run(request, (req, res) -> seen.set(RequestCorrelation.currentRequestId()));

        assertEquals("mdc-check-0001", seen.get());
        assertNull(MDC.get(RequestCorrelation.MDC_REQUEST_ID), "a pooled thread must not keep the id");
        assertEquals("mdc-check-0001", request.getAttribute(RequestCorrelation.REQUEST_ATTRIBUTE));
    }

    @Test
    void theMdcIsCleared_evenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        assertThrows(IllegalStateException.class, () -> run(request, (req, res) -> {
            MDC.put(RequestCorrelation.MDC_SYSTEM_ID, "PAT-00001");
            throw new IllegalStateException("boom");
        }));
        assertNull(MDC.get(RequestCorrelation.MDC_REQUEST_ID));
        assertNull(MDC.get(RequestCorrelation.MDC_SYSTEM_ID), "the caller id is cleared with the request id");
    }

    @Test
    void anErrorDispatch_reusesTheIdOfTheOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse first = run(request, new MockFilterChain());
        String original = header(first);

        // The container re-dispatches the same request to /error; the client header is gone by then on purpose.
        request.setDispatcherType(DispatcherType.ERROR);
        request.removeAttribute(RequestIdFilter.class.getName() + ".FILTERED");
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, (req, res) -> seen.set(RequestCorrelation.currentRequestId()));

        assertEquals(original, seen.get());
        assertEquals(original, header(errorResponse));
    }

    @Test
    void callerMdcFilter_addsTheAuthenticatedSystemId_andRemovesIt() throws Exception {
        CallerMdcFilter callerFilter = new CallerMdcFilter();
        AtomicReference<String> seen = new AtomicReference<>();

        MockHttpServletRequest signedIn = new MockHttpServletRequest("GET", "/api/users/me");
        signedIn.setAttribute(CallerMdcFilter.SYSTEM_ID_ATTRIBUTE, "DOC-00007");
        callerFilter.doFilter(signedIn, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(RequestCorrelation.MDC_SYSTEM_ID)));
        assertEquals("DOC-00007", seen.get());
        assertNull(MDC.get(RequestCorrelation.MDC_SYSTEM_ID));

        seen.set("unchanged");
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/api/auth/login");
        callerFilter.doFilter(anonymous, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(RequestCorrelation.MDC_SYSTEM_ID)));
        assertNull(seen.get(), "an anonymous request has no caller id");
    }

    @Test
    void bothFiltersTogether_logTheRequestIdAndTheCaller() throws Exception {
        CallerMdcFilter callerFilter = new CallerMdcFilter();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> systemId = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader(RequestCorrelation.HEADER, "together-0001");
        request.setAttribute(CallerMdcFilter.SYSTEM_ID_ATTRIBUTE, "PAT-00042");

        run(request, (req, res) -> callerFilter.doFilter(req, res, (r2, s2) -> {
            requestId.set(MDC.get(RequestCorrelation.MDC_REQUEST_ID));
            systemId.set(MDC.get(RequestCorrelation.MDC_SYSTEM_ID));
        }));

        assertEquals("together-0001", requestId.get());
        assertEquals("PAT-00042", systemId.get());
        assertNull(MDC.get(RequestCorrelation.MDC_REQUEST_ID));
        assertNull(MDC.get(RequestCorrelation.MDC_SYSTEM_ID));
    }
}
