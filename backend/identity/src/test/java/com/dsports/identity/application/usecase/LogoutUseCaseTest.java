package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenHasher;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutUseCaseTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TokenHasher tokenHasher;

    private LogoutUseCase logoutUseCase;
    private static final String RAW_TOKEN = "refresh-token-value";
    private static final String HASHED_TOKEN = "hashed-token";
    private static final UserId USER_ID = UserId.generate();

    @BeforeEach
    void setUp() {
        logoutUseCase = new LogoutUseCase(refreshTokenRepository, tokenHasher);
    }

    @Test
    void shouldRevokeTokenOnLogout() {
        var token = RefreshToken.create(USER_ID, HASHED_TOKEN, Instant.now().plus(Duration.ofDays(7)));
        when(tokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(token));
        when(refreshTokenRepository.save(any())).thenReturn(Mono.empty());

        var command = new LogoutCommand(RAW_TOKEN, USER_ID);
        StepVerifier.create(logoutUseCase.execute(command))
                .verifyComplete();

        assertThat(token.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(token);
    }

    @Test
    void shouldDoNothingForUnknownToken() {
        when(refreshTokenRepository.findByToken("hashed-unknown")).thenReturn(Mono.empty());
        when(tokenHasher.hash("unknown")).thenReturn("hashed-unknown");

        var command = new LogoutCommand("unknown", USER_ID);
        StepVerifier.create(logoutUseCase.execute(command))
                .verifyComplete();
    }

    @Test
    void shouldNotRevokeTokenOfDifferentUser() {
        var otherUserId = UserId.generate();
        var token = RefreshToken.create(otherUserId, HASHED_TOKEN, Instant.now().plus(Duration.ofDays(7)));
        when(tokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(token));

        var command = new LogoutCommand(RAW_TOKEN, USER_ID);
        StepVerifier.create(logoutUseCase.execute(command))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertThat(token.isRevoked()).isFalse();
    }
}
