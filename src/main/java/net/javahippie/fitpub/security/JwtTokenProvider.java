package net.javahippie.fitpub.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT token provider for creating and validating authentication tokens.
 * Tokens are used for session management in the REST API.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long validityInMilliseconds;

    /**
     * Known insecure placeholder values that may have leaked into deployment configs.
     * The bean refuses to initialize if the configured secret matches any of them.
     */
    private static final java.util.Set<String> KNOWN_PLACEHOLDERS = java.util.Set.of(
        "change-this-secret-key-in-production-must-be-at-least-32-characters-long",
        "changeme"
    );

    public JwtTokenProvider(
        @Value("${fitpub.security.jwt.secret:}") String secret,
        @Value("${fitpub.security.jwt.expiration:86400000}") long validityInMilliseconds
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT secret is not configured. Set the JWT_SECRET environment variable to a random value of at least 32 characters."
            );
        }
        if (KNOWN_PLACEHOLDERS.contains(secret)) {
            throw new IllegalStateException(
                "JWT secret is set to a known placeholder value. Generate a real secret (e.g. `openssl rand -base64 48`) and set JWT_SECRET."
            );
        }
        // Ensure secret is long enough for HS256 (at least 256 bits / 32 bytes)
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "JWT secret must be at least 32 bytes long (HS256 requirement). Current length: "
                    + secret.getBytes(StandardCharsets.UTF_8).length
            );
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validityInMilliseconds = validityInMilliseconds;
        // Log a fingerprint, never the secret itself
        log.info("JWT signing key initialised (length={} bytes, fingerprint={}…{})",
            secret.getBytes(StandardCharsets.UTF_8).length,
            secret.substring(0, Math.min(4, secret.length())),
            secret.substring(Math.max(0, secret.length() - 4))
        );
    }

    /**
     * Creates a JWT token for an authenticated user.
     *
     * @param authentication the authentication object
     * @return JWT token string
     */
    public String createToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return createToken(userDetails.getUsername());
    }

    /**
     * Creates a JWT token for a username.
     *
     * @param username the username
     * @return JWT token string
     */
    public String createToken(String username) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + validityInMilliseconds);

        return Jwts.builder()
            .setSubject(username)
            .setIssuedAt(now)
            .setExpiration(validity)
            .signWith(secretKey, SignatureAlgorithm.HS256)
            .compact();
    }

    /**
     * Extracts the username from a JWT token.
     *
     * @param token the JWT token
     * @return username
     */
    public String getUsername(String token) {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
    }

    /**
     * Validates a JWT token.
     *
     * @param token the JWT token
     * @return true if token is valid
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the token from the Authorization header.
     *
     * @param bearerToken the Authorization header value
     * @return the token or null if not found
     */
    public String resolveToken(String bearerToken) {
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
