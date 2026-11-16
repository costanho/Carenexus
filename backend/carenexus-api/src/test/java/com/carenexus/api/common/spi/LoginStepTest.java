package com.carenexus.api.common.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LoginStepTest {

    @Test
    void proceed_hasNoChallenge() {
        LoginStep step = LoginStep.proceed();
        assertTrue(step.canProceed());
        assertFalse(step.isChallenge());
        assertNull(step.challengeType());
        assertNull(step.challengeToken());
        assertEquals(0, step.expiresInSeconds());
    }

    @Test
    void challenge_carriesTypeTokenAndLifetime_andHidesTheTokenInToString() {
        LoginStep step = LoginStep.challenge(LoginStep.MFA, "opaque-challenge-token", 300);
        assertFalse(step.canProceed());
        assertTrue(step.isChallenge());
        assertEquals("MFA", step.challengeType());
        assertEquals("opaque-challenge-token", step.challengeToken());
        assertEquals(300, step.expiresInSeconds());
        assertFalse(step.toString().contains("opaque-challenge-token"));
    }

    @Test
    void challenge_requiresTypeAndToken() {
        assertThrows(IllegalArgumentException.class, () -> LoginStep.challenge(" ", "token", 300));
        assertThrows(IllegalArgumentException.class, () -> LoginStep.challenge(LoginStep.MFA_SETUP, null, 300));
    }
}
