package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenProvider tokenProvider;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    private LoginUseCase loginUseCase;
    private User activeUser;

    @BeforeEach
    void setUp() {
        loginUseCase = new LoginUseCase(userRepository, passwordEncoder, tokenProvider, refreshTokenRepository);
        var email = Email.from("user@example.com");
        var userId = com.dsports.identity.domain.model.UserId.generate();
        activeUser = User.reconstitute(
                userId, email, "hashedPassword",
                CustomerName.of("John", "Doe"), null,
                com.dsports.identity.domain.model.UserStatus.ACTIVE,
                java.util.Set.of(com.dsports.identity.domain.model.UserRole.CUSTOMER),
                java.util.Set.of(com.dsports.identity.domain.model.AuthenticationProvider.EMAIL),
                0, null, null,
                java.time.Instant.now(), java.time.Instant.now(), null
        );
    }

    @Test
    void shouldSucceedWithValidCredentials() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        when(tokenProvider.generateAccessToken(activeUser)).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken()).thenReturn("refresh-token");
        when(tokenProvider.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));

        var command = new LoginUserCommand("user@example.com", "password");
        var result = loginUseCase.execute(command);

        assertThat(result.success()).isTrue();
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(userRepository).save(activeUser);
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void shouldFailWithWrongPassword() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("wrong", "hashedPassword")).thenReturn(false);

        var command = new LoginUserCommand("user@example.com", "wrong");
        var result = loginUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo(AuthenticationFailureReason.INVALID_PASSWORD);
    }

    @Test
    void shouldFailForNonexistentUser() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        var command = new LoginUserCommand("unknown@example.com", "password");
        var result = loginUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo(AuthenticationFailureReason.USER_NOT_FOUND);
    }

    @Test
    void shouldFailForLockedAccount() {
        activeUser.recordFailedLogin();
        activeUser.recordFailedLogin();
        activeUser.recordFailedLogin();
        activeUser.recordFailedLogin();
        activeUser.recordFailedLogin();

        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));

        var command = new LoginUserCommand("user@example.com", "password");
        var result = loginUseCase.execute(command);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo(AuthenticationFailureReason.ACCOUNT_LOCKED);
    }
}
