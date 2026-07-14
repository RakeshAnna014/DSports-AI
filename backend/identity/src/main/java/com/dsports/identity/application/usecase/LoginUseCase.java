package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.application.result.LoginResult;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;

import java.time.Instant;

public class LoginUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    public LoginUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        TokenProvider tokenProvider, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public LoginResult execute(LoginUserCommand command) {
        var email = Email.from(command.email());

        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return LoginResult.failure(AuthenticationFailureReason.USER_NOT_FOUND);
        }

        var user = userOpt.get();

        if (user.getStatus().isDeleted()) {
            return LoginResult.failure(AuthenticationFailureReason.ACCOUNT_DELETED);
        }

        if (user.getStatus().isDisabled()) {
            return LoginResult.failure(AuthenticationFailureReason.ACCOUNT_DISABLED);
        }

        if (user.isLocked()) {
            userRepository.save(user);
            return LoginResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED);
        }

        if (!user.canLogin()) {
            return LoginResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED);
        }

        var passwordHash = user.getPasswordHash();
        if (passwordHash == null || !passwordEncoder.matches(command.password(), passwordHash)) {
            user.recordFailedLogin();
            userRepository.save(user);
            return LoginResult.failure(AuthenticationFailureReason.INVALID_PASSWORD);
        }

        user.updateLastLogin();
        userRepository.save(user);

        var accessToken = tokenProvider.generateAccessToken(user);
        var rawRefreshToken = tokenProvider.generateRefreshToken();
        var expiry = Instant.now().plus(tokenProvider.getRefreshTokenExpiration());

        var refreshToken = RefreshToken.create(user.getId(), rawRefreshToken, expiry);
        refreshTokenRepository.save(refreshToken);

        var roles = user.getRoles().stream()
                .map(Enum::name)
                .toList();

        return LoginResult.success(user.getId().value(), user.getEmail().value(), roles, accessToken, rawRefreshToken);
    }
}
