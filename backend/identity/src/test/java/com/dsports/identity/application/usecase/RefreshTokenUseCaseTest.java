package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenUseCaseTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private TokenProvider tokenProvider;

    private RefreshTokenUseCase refreshTokenUseCase;
    private User activeUser;
    private RefreshToken validToken;
    private static final String RAW_TOKEN = "refresh-token-value";
    private static final UserId USER_ID = UserId.generate();

    @BeforeEach
    void setUp() {
        refreshTokenUseCase = new RefreshTokenUseCase(refreshTokenRepository, userRepository, tokenProvider);
        var email = Email.from("user@example.com");
        activeUser = User.register(email, CustomerName.of("John", "Doe"), "hash");
        validToken = RefreshToken.create(USER_ID, RAW_TOKEN, Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    void shouldSucceedWithValidToken() {
        when(refreshTokenRepository.findByToken(RAW_TOKEN)).thenReturn(Optional.of(validToken));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser));
        when(tokenProvider.generateAccessToken(activeUser)).thenReturn("new-access-token");
        when(tokenProvider.generateRefreshToken()).thenReturn("new-refresh-token");
        when(tokenProvider.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        var result = refreshTokenUseCase.execute(command);

        assertThat(result.success()).isTrue();
        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    void shouldFailWithNonexistentToken() {
        when(refreshTokenRepository.findByToken("unknown-token")).thenReturn(Optional.empty());

        var command = new RefreshTokenCommand("unknown-token");
        var result = refreshTokenUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("REFRESH_TOKEN_NOT_FOUND");
    }

    @Test
    void shouldFailWithRevokedToken() {
        validToken.revoke();
        when(refreshTokenRepository.findByToken(RAW_TOKEN)).thenReturn(Optional.of(validToken));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        var result = refreshTokenUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("REFRESH_TOKEN_REVOKED");
    }

    @Test
    void shouldFailWithExpiredToken() {
        var expiredToken = RefreshToken.reconstitute(
                com.dsports.identity.domain.model.RefreshTokenId.generate(),
                USER_ID, RAW_TOKEN,
                Instant.now().minus(Duration.ofHours(1)),
                Instant.now().minus(Duration.ofDays(8)),
                false
        );
        when(refreshTokenRepository.findByToken(RAW_TOKEN)).thenReturn(Optional.of(expiredToken));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        var result = refreshTokenUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("REFRESH_TOKEN_EXPIRED");
    }
}
