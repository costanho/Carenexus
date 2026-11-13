package com.carenexus.api.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil: which signing secrets it refuses to start with, and which tokens it accepts.
 */
class JwtUtilTest {

    private static final String REAL_SECRET = "a-real-secret-generated-for-this-test-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String OTHER_SECRET = "another-real-secret-generated-for-this-test-0123456789-abcdefghijklmnopq";
    private static final String LOCAL_DEV_SECRET =
            "local-dev-only-jwt-secret-NOT-FOR-PRODUCTION-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String DOCKER_DEV_SECRET =
            "docker-dev-only-jwt-secret-NOT-FOR-PRODUCTION-0123456789abcdefghijklmnopqrstuvwxyz";

    private static JwtUtil configured(String secret, long expirationMillis, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        JwtUtil jwtUtil = new JwtUtil(environment);
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", secret);
        ReflectionTestUtils.setField(jwtUtil, "jwtExpiration", expirationMillis);
        return jwtUtil;
    }

    private static JwtUtil started(String secret, String... profiles) {
        JwtUtil jwtUtil = configured(secret, 900_000L, profiles);
        jwtUtil.init();
        return jwtUtil;
    }

    // ==================== SECRETS ====================

    @Test
    void blankSecret_failsStartup_inEveryProfile() {
        for (String secret : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> configured(secret, 900_000L, "prod").init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret is not configured");
            assertThatThrownBy(() -> configured(secret, 900_000L, "local").init())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void burnedSecrets_failStartup_evenOnADeveloperProfile() {
        List<String> burned = List.of(
                "your-super-secret-key-that-is-at-least-32-characters-long-change-in-production",
                "your-secret-key-min-32-chars-long-for-production");
        for (String secret : burned) {
            for (String profile : List.of("prod", "local", "docker", "test")) {
                assertThatThrownBy(() -> configured(secret, 900_000L, profile).init())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("committed to the repository");
            }
        }
    }

    @Test
    void devOnlySecrets_failStartupOutsideDeveloperProfiles_andAreAcceptedInside() {
        for (String secret : List.of(LOCAL_DEV_SECRET, DOCKER_DEV_SECRET)) {
            assertThatThrownBy(() -> configured(secret, 900_000L, "prod").init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("development-only");
            assertThatThrownBy(() -> configured(secret, 900_000L).init())   // no profile is not a developer profile
                    .isInstanceOf(IllegalStateException.class);
            for (String profile : List.of("local", "docker", "test")) {
                assertThatCode(() -> configured(secret, 900_000L, profile).init()).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void shortSecret_failsStartup_and64BytesIsEnough() {
        assertThatThrownBy(() -> configured("x".repeat(63), 900_000L, "prod").init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 64 bytes")
                .hasMessageContaining("got 63");
        assertThatThrownBy(() -> configured("short", 900_000L, "local").init())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> configured("x".repeat(64), 900_000L, "prod").init()).doesNotThrowAnyException();
    }

    @Test
    void errorMessages_neverContainTheSecret() {
        assertThatThrownBy(() -> configured("x".repeat(40) + "-do-not-print-me", 900_000L, "prod").init())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("do-not-print-me"));
        assertThatThrownBy(() -> configured(LOCAL_DEV_SECRET, 900_000L, "prod").init())
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(LOCAL_DEV_SECRET));
    }

    // ==================== TOKENS ====================

    @Test
    void issuedToken_carriesTheClaims_andIsAccepted() {
        JwtUtil jwtUtil = started(REAL_SECRET, "prod");

        String token = jwtUtil.generateToken(17, "PAT-00012", "linda@example.com", "PATIENT");

        Optional<Claims> claims = jwtUtil.parse(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().get("userId", Integer.class)).isEqualTo(17);
        assertThat(claims.get().get("systemId", String.class)).isEqualTo("PAT-00012");
        assertThat(claims.get().get("role", String.class)).isEqualTo("PATIENT");
        assertThat(claims.get().get("email", String.class)).isEqualTo("linda@example.com");
        assertThat(claims.get().getSubject()).isEqualTo("linda@example.com");
        assertThat(claims.get().getIssuer()).isEqualTo("carenexus-api");
        assertThat(claims.get().getExpiration()).isAfter(new Date());
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getSystemIdFromToken(token)).isEqualTo("PAT-00012");
        assertThat(jwtUtil.getRoleFromToken(token)).isEqualTo("PATIENT");
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(17);
        assertThat(jwtUtil.getExpirationTimeInSeconds()).isEqualTo(900);
        // The token carries no session claim.
        assertThat(claims.get()).doesNotContainKeys("sessionId", "sid");
    }

    @Test
    void tokenSignedWithAnotherSecret_expiredToken_otherIssuer_andGarbage_areRejected() {
        JwtUtil jwtUtil = started(REAL_SECRET, "prod");

        String forged = started(OTHER_SECRET, "prod").generateToken(1, "ADM-00001", "admin@example.com", "ADMIN");
        assertThat(jwtUtil.parse(forged)).isEmpty();
        assertThat(jwtUtil.validateToken(forged)).isFalse();

        JwtUtil alreadyExpired = configured(REAL_SECRET, -1_000L, "prod");
        alreadyExpired.init();
        assertThat(jwtUtil.parse(alreadyExpired.generateToken(1, "ADM-00001", "admin@example.com", "ADMIN"))).isEmpty();

        var key = Keys.hmacShaKeyFor(REAL_SECRET.getBytes(StandardCharsets.UTF_8));
        String otherIssuer = Jwts.builder().claim("systemId", "ADM-00001").subject("admin@example.com")
                .issuer("some-other-service").expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS512).compact();
        String noIssuer = Jwts.builder().claim("systemId", "ADM-00001").subject("admin@example.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS512).compact();
        String unsigned = Jwts.builder().claim("systemId", "ADM-00001").subject("admin@example.com")
                .issuer("carenexus-api").expiration(new Date(System.currentTimeMillis() + 60_000)).compact();
        assertThat(jwtUtil.parse(otherIssuer)).isEmpty();
        assertThat(jwtUtil.parse(noIssuer)).isEmpty();
        assertThat(jwtUtil.parse(unsigned)).isEmpty();

        for (String garbage : new String[]{null, "", "   ", "not-a-jwt", "a.b.c"}) {
            assertThat(jwtUtil.parse(garbage)).isEmpty();
        }
    }
}
