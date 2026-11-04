package com.dsports.identity.infrastructure.security;

import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "this-is-a-secret-key-that-is-long-enough-for-hs256-algorithm-123456";
    private static final Duration ACCESS_EXPIRY = Duration.ofMinutes(15);
    private static final Duration REFRESH_EXPIRY = Duration.ofDays(7);

    private JwtTokenProvider tokenProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY);
        var email = Email.from("test@example.com");
        testUser = User.register(email, com.dsports.identity.domain.model.CustomerName.of("Test", "User"), "hashedPassword");
    }

    @Test
    void shouldGenerateValidAccessToken() {
        var token = tokenProvider.generateAccessToken(testUser);

        assertThat(token).isNotBlank();
        var userId = tokenProvider.extractUserId(token);
        assertThat(UUID.fromString(userId)).isEqualTo(testUser.getId().value());
    }

    @Test
    void shouldExtractRolesFromToken() {
        var token = tokenProvider.generateAccessToken(testUser);

        var roles = tokenProvider.extractRoles(token);

        assertThat(roles).contains("CUSTOMER");
    }

    @Test
    void shouldGenerateRefreshToken() {
        var token = tokenProvider.generateRefreshToken();

        assertThat(token).isNotBlank();
        assertThat(token).contains("-");
    }

    @Test
    void shouldRejectExpiredToken() {
        var shortExpiryProvider = new JwtTokenProvider(SECRET, Duration.ofNanos(1), REFRESH_EXPIRY);
        var token = shortExpiryProvider.generateAccessToken(testUser);

        assertThatThrownBy(() -> shortExpiryProvider.extractUserId(token))
                .isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldRejectInvalidToken() {
        assertThatThrownBy(() -> tokenProvider.extractUserId("invalid-token"))
                .isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldRejectTokenFromDifferentSecret() {
        var otherProvider = new JwtTokenProvider("different-secret-key-that-is-long-enough-for-hs256-algorithm-654321", ACCESS_EXPIRY, REFRESH_EXPIRY);
        var token = tokenProvider.generateAccessToken(testUser);

        assertThatThrownBy(() -> otherProvider.extractUserId(token))
                .isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldReturnRefreshTokenExpiration() {
        assertThat(tokenProvider.getRefreshTokenExpiration()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void shouldExtractEmailFromToken() {
        var token = tokenProvider.generateAccessToken(testUser);

        var claims = tokenProvider.validate(token);
        assertThat(claims.get("email", String.class)).isEqualTo("test@example.com");
    }

    @Test
    void shouldIncludeJtiClaim() {
        var token = tokenProvider.generateAccessToken(testUser);

        var claims = tokenProvider.validate(token);
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void shouldRejectSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtTokenProvider("short-key", ACCESS_EXPIRY, REFRESH_EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(null, ACCESS_EXPIRY, REFRESH_EXPIRY))
                .isInstanceOf(Exception.class);
    }
    @Test
    void shouldNameIssuerAndAudienceInTheToken() {
        var claims = tokenProvider.validate(tokenProvider.generateAccessToken(testUser));

        assertThat(claims.getIssuer()).isEqualTo(JwtTokenProvider.DEFAULT_ISSUER);
        assertThat(claims.getAudience()).containsExactly(JwtTokenProvider.DEFAULT_AUDIENCE);
    }

    @Test
    void shouldRejectAValidlySignedTokenMintedForAnotherAudience() {
        var foreign = new JwtTokenProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY, JwtTokenProvider.DEFAULT_ISSUER, "some-other-service");
        var token = foreign.generateAccessToken(testUser);

        assertThatThrownBy(() -> tokenProvider.validate(token)).isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldRejectAValidlySignedTokenFromAnotherIssuer() {
        var foreign = new JwtTokenProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY, "someone-else", JwtTokenProvider.DEFAULT_AUDIENCE);
        var token = foreign.generateAccessToken(testUser);

        assertThatThrownBy(() -> tokenProvider.validate(token)).isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldRejectATokenWithNoIssuerOrAudienceEvenIfSignedWithTheRightSecret() {
        var bare = io.jsonwebtoken.Jwts.builder().subject(testUser.getId().value().toString())
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();

        assertThatThrownBy(() -> tokenProvider.validate(bare)).isInstanceOf(JwtTokenProvider.JwtValidationException.class);
    }

    @Test
    void shouldRejectBlankIssuerOrAudienceAtStartup() {
        assertThatThrownBy(() -> new JwtTokenProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY, " ", "aud")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtTokenProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY, "iss", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
