package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.RefreshTokenHasher;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.application.result.LoginResult;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

public class LoginUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;

    public LoginUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        TokenProvider tokenProvider, RefreshTokenRepository refreshTokenRepository,
                        RefreshTokenHasher refreshTokenHasher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
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
                    // BCrypt is deliberately slow CPU work: keep it off the event loop.
                    return Mono.fromCallable(() -> passwordHash != null && passwordEncoder.matches(command.password(), passwordHash))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(passwordMatches -> {
                                if (!passwordMatches) {
                                    user.recordFailedLogin();
                                    return userRepository.save(user)
                                            .thenReturn(LoginResult.failure(AuthenticationFailureReason.INVALID_PASSWORD));
                                }
                                return issueTokens(user, command);
                            });
                })
                .switchIfEmpty(Mono.just(LoginResult.failure(AuthenticationFailureReason.USER_NOT_FOUND)));
    }

    private Mono<LoginResult> issueTokens(User user, LoginUserCommand command) {
        user.updateLastLogin();
        return userRepository.save(user)
                .then(Mono.defer(() -> {
                    var accessToken = tokenProvider.generateAccessToken(user);
                    var rawRefreshToken = tokenProvider.generateRefreshToken();
                    var hashedToken = refreshTokenHasher.hash(rawRefreshToken);
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
    }
}
