package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.RefreshTokenHasher;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.RefreshFailureReason;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenUseCaseTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private TokenProvider tokenProvider;
    @Mock private RefreshTokenHasher refreshTokenHasher;

    private RefreshTokenUseCase refreshTokenUseCase;
    private User activeUser;
    private RefreshToken validToken;
    private static final String RAW_TOKEN = "refresh-token-value";
    private static final String HASHED_TOKEN = "hashed-token";
    private static final UserId USER_ID = UserId.generate();

    @BeforeEach
    void setUp() {
        refreshTokenUseCase = new RefreshTokenUseCase(refreshTokenRepository, userRepository, tokenProvider, refreshTokenHasher);
        var email = Email.from("user@example.com");
        activeUser = User.reconstitute(
                USER_ID, email, "hash",
                CustomerName.of("John", "Doe"), null,
                UserStatus.ACTIVE, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                Instant.now(), Instant.now(), null
        );
        validToken = RefreshToken.create(USER_ID, HASHED_TOKEN, Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    void shouldSucceedWithValidToken() {
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenHasher.hash("new-refresh-token")).thenReturn("new-hashed-token");
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(validToken));
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(activeUser));
        when(tokenProvider.generateAccessToken(activeUser)).thenReturn("new-access-token");
        when(tokenProvider.generateRefreshToken()).thenReturn("new-refresh-token");
        when(tokenProvider.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));
        when(refreshTokenRepository.save(any())).thenReturn(Mono.empty());

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.accessToken()).isEqualTo("new-access-token");
                    assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
                })
                .verifyComplete();

        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    void shouldFailWithNonexistentToken() {
        when(refreshTokenRepository.findByToken("hashed-unknown")).thenReturn(Mono.empty());
        when(refreshTokenHasher.hash("unknown-token")).thenReturn("hashed-unknown");

        var command = new RefreshTokenCommand("unknown-token");
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.TOKEN_NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWithRevokedToken() {
        validToken.revoke();
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(validToken));
        when(refreshTokenRepository.revokeByUserId(any())).thenReturn(Mono.empty());

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.TOKEN_REVOKED);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWithExpiredToken() {
        var expiredToken = RefreshToken.reconstitute(
                com.dsports.identity.domain.model.RefreshTokenId.generate(),
                USER_ID, HASHED_TOKEN,
                Instant.now().minus(Duration.ofHours(1)),
                Instant.now().minus(Duration.ofDays(8)),
                false
        );
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(expiredToken));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.TOKEN_EXPIRED);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailForDeletedUser() {
        var deletedUser = User.reconstitute(
                USER_ID, Email.from("deleted@example.com"), "hash",
                CustomerName.of("Deleted", "User"), null,
                UserStatus.DELETED, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                Instant.now(), Instant.now(), Instant.now()
        );
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(validToken));
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(deletedUser));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.USER_DELETED);
                })
                .verifyComplete();

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void shouldFailForDisabledUser() {
        var disabledUser = User.reconstitute(
                USER_ID, Email.from("disabled@example.com"), "hash",
                CustomerName.of("Disabled", "User"), null,
                UserStatus.DISABLED, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                Instant.now(), Instant.now(), null
        );
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(validToken));
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(disabledUser));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.USER_DISABLED);
                })
                .verifyComplete();

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void shouldFailForLockedUser() {
        var lockedUser = User.reconstitute(
                USER_ID, Email.from("locked@example.com"), "hash",
                CustomerName.of("Locked", "User"), null,
                UserStatus.LOCKED, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                5, Instant.now().plus(Duration.ofHours(1)), null,
                Instant.now(), Instant.now(), null
        );
        when(refreshTokenHasher.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(refreshTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Mono.just(validToken));
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(lockedUser));

        var command = new RefreshTokenCommand(RAW_TOKEN);
        StepVerifier.create(refreshTokenUseCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo(RefreshFailureReason.USER_LOCKED);
                })
                .verifyComplete();

        verify(refreshTokenRepository, never()).save(any());
    }
}
