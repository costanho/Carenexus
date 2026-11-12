package com.carenexus.api.common.ratelimit;

import com.carenexus.api.common.exception.GlobalExceptionHandler;
import com.carenexus.api.common.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RateLimitGuard: the 429 envelope a client receives (status, code, Retry-After, message), the enabled switch and
 * reset. Standalone MockMvc with the real GlobalExceptionHandler, no database.
 */
public class RateLimitGuardTest {

    private RateLimitProperties properties;
    private MutableClock clock;
    private RateLimitGuard guard;
    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/t")
    static class LimitedController {
        private final RateLimitGuard guard;

        LimitedController(RateLimitGuard guard) {
            this.guard = guard;
        }

        @PostMapping("/register")
        public void register(HttpServletRequest request) {
            guard.check("auth-forgot-email", request.getRemoteAddr());   // 3 per hour
        }
    }

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
        guard = new RateLimitGuard(new InMemoryRateLimiter(properties, clock), properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new LimitedController(guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void overTheLimit_is429_withEnvelopeCodeAndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/t/register")).andExpect(status().isOk());
        }
        clock.advance(Duration.ofMinutes(10));

        mockMvc.perform(post("/t/register"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3000"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again in 50 minutes."))
                .andExpect(jsonPath("$.path").value("/t/register"));

        clock.advance(Duration.ofMinutes(50));
        mockMvc.perform(post("/t/register")).andExpect(status().isOk());
    }

    @Test
    void anotherCallerIsNotAffected() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/t/register").with(r -> { r.setRemoteAddr("203.0.113.1"); return r; }));
        }
        mockMvc.perform(post("/t/register").with(r -> { r.setRemoteAddr("203.0.113.1"); return r; }))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/t/register").with(r -> { r.setRemoteAddr("203.0.113.2"); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void disabled_neverRefuses() {
        properties.setEnabled(false);
        for (int i = 0; i < 50; i++) {
            assertDoesNotThrow(() -> guard.check("auth-forgot-email", "k"));
        }
    }

    @Test
    void check_throwsWithRetryAfter_andResetClearsTheKey() {
        for (int i = 0; i < 5; i++) {
            guard.check("password-change", "PAT-00001");
        }
        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class,
                () -> guard.check("password-change", "PAT-00001"));
        assertEquals(900, ex.getRetryAfterSeconds());

        guard.reset("password-change", "PAT-00001");
        assertDoesNotThrow(() -> guard.check("password-change", "PAT-00001"));
    }

    @Test
    void message_usesSecondsMinutesOrHours() {
        assertEquals("Too many requests. Please try again in 1 second.", RateLimitGuard.message(1));
        assertEquals("Too many requests. Please try again in 45 seconds.", RateLimitGuard.message(45));
        assertEquals("Too many requests. Please try again in 1 minute.", RateLimitGuard.message(60));
        assertEquals("Too many requests. Please try again in 15 minutes.", RateLimitGuard.message(900));
        assertEquals("Too many requests. Please try again in 24 hours.", RateLimitGuard.message(86400));
    }
}
