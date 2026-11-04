package com.dsports.identity.infrastructure.security;

import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class JwtTokenProvider implements TokenProvider {

    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;
    private final String issuer;
    private final String audience;

    public static final String DEFAULT_ISSUER = "dsports";
    public static final String DEFAULT_AUDIENCE = "dsports-api";

    public JwtTokenProvider(String secret, Duration accessTokenExpiration, Duration refreshTokenExpiration) {
        this(secret, accessTokenExpiration, refreshTokenExpiration, DEFAULT_ISSUER, DEFAULT_AUDIENCE);
    }

    /** Access tokens name their issuer and audience, and both are required on validation: a validly signed token minted for another service is refused. */
    public JwtTokenProvider(String secret, Duration accessTokenExpiration, Duration refreshTokenExpiration, String issuer, String audience) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 256 bits (32 characters)");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT issuer and audience must not be blank");
        }
        this.issuer = issuer;
        this.audience = audience;
    }

    public String generateAccessToken(User user) {
        var now = Instant.now();
        var roles = user.getRoles().stream()
                .map(UserRole::name)
                .toList();

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(user.getId().value().toString())
                .claim("email", user.getEmail().value())
                .claim("roles", roles)
                .claim("provider", user.getAuthProviders().stream()
                        .findFirst()
                        .map(Object::toString)
                        .orElse("EMAIL"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    public Duration getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public Claims validate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtValidationException("Token has expired", e);
        } catch (JwtException e) {
            throw new JwtValidationException("Invalid token", e);
        }
    }

    public String extractUserId(String token) {
        return validate(token).getSubject();
    }

    public List<String> extractRoles(String token) {
        @SuppressWarnings("unchecked")
        var roles = validate(token).get("roles", List.class);
        return roles.stream().map(Object::toString).toList();
    }

    public Instant extractExpiration(String token) {
        return validate(token).getExpiration().toInstant();
    }

    public static class JwtValidationException extends RuntimeException {
        public JwtValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
