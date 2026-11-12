package com.carenexus.api.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/** The Prometheus scrape-token filter on its own; the route rules around it are covered by ObservabilityIntegrationTest. */
class MetricsScrapeAuthenticationFilterTest {

    private static final String TOKEN = "unit-scrape-token-0123456789abcdef0123";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(MetricsScrapeAuthenticationFilter filter, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "the request always continues down the chain");
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void theConfiguredToken_authenticatesTheScrapePathAsTheScraper() throws Exception {
        Authentication auth = run(new MetricsScrapeAuthenticationFilter(TOKEN), "/actuator/prometheus", "Bearer " + TOKEN);
        assertNotNull(auth);
        assertEquals(MetricsScrapeAuthenticationFilter.PRINCIPAL, auth.getPrincipal());
        assertEquals(1, auth.getAuthorities().size());
        assertEquals(MetricsScrapeAuthenticationFilter.AUTHORITY, auth.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void otherPathsAndWrongTokens_stayUnauthenticated() throws Exception {
        MetricsScrapeAuthenticationFilter filter = new MetricsScrapeAuthenticationFilter(TOKEN);
        assertNull(run(filter, "/api/users/me", "Bearer " + TOKEN));
        assertNull(run(filter, "/actuator/prometheus", "Bearer wrong-" + TOKEN));
        assertNull(run(filter, "/actuator/prometheus", null));
    }

    @Test
    void withoutAToken_theFilterIsOff() throws Exception {
        MetricsScrapeAuthenticationFilter filter = new MetricsScrapeAuthenticationFilter("  ");
        assertFalse(filter.isEnabled());
        assertNull(run(filter, "/actuator/prometheus", "Bearer "));
        assertNull(run(filter, "/actuator/prometheus", "Bearer  "));
    }

    @Test
    void aShortToken_refusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MetricsScrapeAuthenticationFilter("too-short"));
        assertTrue(e.getMessage().contains("METRICS_SCRAPE_TOKEN"));
        assertFalse(e.getMessage().contains("too-short"), "the configured value is never echoed");
    }
}
