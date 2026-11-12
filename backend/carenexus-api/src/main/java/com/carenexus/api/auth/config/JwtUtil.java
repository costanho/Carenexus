package com.carenexus.api.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class JwtUtil {

    static final String ISSUER = "carenexus-api";

    /** HS512 needs a key of at least 512 bits. */
    private static final int MIN_SECRET_BYTES = 64;

    /**
     * Secrets that were committed to the repository at some point. They are public, so tokens
     * signed with them can be forged by anyone; refuse to start with them in any profile.
     */
    private static final Set<String> BURNED_SECRETS = Set.of(
            "your-super-secret-key-that-is-at-least-32-characters-long-change-in-production",
            "your-secret-key-min-32-chars-long-for-production"
    );

    /**
     * Dev-only secrets that are committed on purpose (application.yml 'local' profile and
     * .env.example). Accepted only when a local/docker/test profile is active.
     */
    private static final Set<String> DEV_ONLY_SECRETS = Set.of(
            "local-dev-only-jwt-secret-NOT-FOR-PRODUCTION-0123456789abcdefghijklmnopqrstuvwxyz",
            "docker-dev-only-jwt-secret-NOT-FOR-PRODUCTION-0123456789abcdefghijklmnopqrstuvwxyz"
    );

    private final Environment environment;

    // No default: the secret must come from JWT_SECRET (or a dev profile). See application.yml.
    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.expiration:900000}")  // 15 minutes in milliseconds
    private long jwtExpiration;

    private SecretKey signingKey;

    public JwtUtil(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set the JWT_SECRET environment variable "
                            + "(generate one with: openssl rand -base64 64).");
        }
        if (BURNED_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is set to a value that was committed to the repository and is public. "
                            + "Generate a new one with: openssl rand -base64 64");
        }
        if (DEV_ONLY_SECRETS.contains(jwtSecret)
                && !environment.acceptsProfiles(Profiles.of("local", "docker", "test"))) {
            throw new IllegalStateException(
                    "JWT_SECRET is set to a development-only value outside the local/docker profiles. "
                            + "Generate a real one with: openssl rand -base64 64");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS512 (got "
                            + keyBytes.length + "). Generate one with: openssl rand -base64 64");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Integer userId, String systemId, String email, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("systemId", systemId);
        claims.put("role", role);
        claims.put("email", email);

        return createToken(claims, email);
    }

    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuer(ISSUER)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Verify signature, expiry and issuer once and return the claims, or empty if the token
     * is not acceptable for any reason.
     */
    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean validateToken(String token) {
        return parse(token).isPresent();
    }

    public Integer getUserIdFromToken(String token) {
        Object userId = getAllClaimsFromToken(token).get("userId");
        return userId instanceof Number n ? n.intValue() : null;
    }

    public String getSystemIdFromToken(String token) {
        return getAllClaimsFromToken(token).get("systemId", String.class);
    }

    public String getRoleFromToken(String token) {
        return getAllClaimsFromToken(token).get("role", String.class);
    }

    public String getEmailFromToken(String token) {
        return getAllClaimsFromToken(token).getSubject();
    }

    private Claims getAllClaimsFromToken(String token) {
        return parse(token).orElseThrow(() -> new JwtException("Invalid token"));
    }

    public long getExpirationTimeInSeconds() {
        return jwtExpiration / 1000;
    }
}
