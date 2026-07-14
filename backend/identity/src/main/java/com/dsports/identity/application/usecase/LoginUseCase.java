package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenHasher;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.application.result.LoginResult;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class LoginUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenHasher tokenHasher;

    public LoginUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        TokenProvider tokenProvider, RefreshTokenRepository refreshTokenRepository,
                        TokenHasher tokenHasher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenHasher = tokenHasher;
    }

    public Mono<LoginResult> execute(LoginUserCommand command) {
        var email = Email.from(command.email());

        return userRepository.findByEmail(email)
                .flatMap(user -> {
                    if (user.getStatus().isDeleted()) {
                        return Mono.just(LoginResult.failure(AuthenticationFailureReason.ACCOUNT_DELETED));
                    }

                    if (user.getStatus().isDisabled()) {
                        return Mono.just(LoginResult.failure(AuthenticationFailureReason.ACCOUNT_DISABLED));
                    }

                    if (user.isLocked()) {
                        return userRepository.save(user)
                                .thenReturn(LoginResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED));
                    }

                    if (!user.canLogin()) {
                        return Mono.just(LoginResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED));
                    }

                    var passwordHash = user.getPasswordHash();
                    if (passwordHash == null || !passwordEncoder.matches(command.password(), passwordHash)) {
                        user.recordFailedLogin();
                        return userRepository.save(user)
                                .thenReturn(LoginResult.failure(AuthenticationFailureReason.INVALID_PASSWORD));
                    }

                    user.updateLastLogin();
                    return userRepository.save(user)
                            .then(Mono.defer(() -> {
                                var accessToken = tokenProvider.generateAccessToken(user);
                                var rawRefreshToken = tokenProvider.generateRefreshToken();
                                var hashedToken = tokenHasher.hash(rawRefreshToken);
                                var expiry = Instant.now().plus(tokenProvider.getRefreshTokenExpiration());

                                var refreshToken = RefreshToken.create(
                                        user.getId(), hashedToken, expiry,
                                        command.deviceName(), command.userAgent(), command.ipAddress());
                                var roles = user.getRoles().stream()
                                        .map(Enum::name)
                                        .toList();

                                return refreshTokenRepository.save(refreshToken)
                                        .thenReturn(LoginResult.success(
                                                user.getId().value(), user.getEmail().value(),
                                                roles, accessToken, rawRefreshToken));
                            }));
                })
                .switchIfEmpty(Mono.just(LoginResult.failure(AuthenticationFailureReason.USER_NOT_FOUND)));
    }
}
