package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.RefreshTokenId;
import com.dsports.identity.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutUseCaseTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;

    private LogoutUseCase logoutUseCase;
    private static final String RAW_TOKEN = "refresh-token-value";

    @BeforeEach
    void setUp() {
        logoutUseCase = new LogoutUseCase(refreshTokenRepository);
    }

    @Test
    void shouldRevokeTokenOnLogout() {
        var token = RefreshToken.create(UserId.generate(), RAW_TOKEN, Instant.now().plus(Duration.ofDays(7)));
        when(refreshTokenRepository.findByToken(RAW_TOKEN)).thenReturn(Optional.of(token));

        var command = new LogoutCommand(RAW_TOKEN);
        logoutUseCase.execute(command);

        assertThat(token.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(token);
    }

    @Test
    void shouldDoNothingForUnknownToken() {
        when(refreshTokenRepository.findByToken("unknown")).thenReturn(Optional.empty());

        var command = new LogoutCommand("unknown");
        logoutUseCase.execute(command);

        verify(refreshTokenRepository).findByToken("unknown");
    }
}
