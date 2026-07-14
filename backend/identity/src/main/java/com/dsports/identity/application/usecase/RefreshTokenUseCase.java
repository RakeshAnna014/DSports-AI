package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.RefreshTokenResult;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.User;

import java.time.Instant;

public class RefreshTokenUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;

    public RefreshTokenUseCase(RefreshTokenRepository refreshTokenRepository,
                                UserRepository userRepository,
                                TokenProvider tokenProvider) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
    }

    public RefreshTokenResult execute(RefreshTokenCommand command) {
        var storedTokenOpt = refreshTokenRepository.findByToken(command.refreshToken());
        if (storedTokenOpt.isEmpty()) {
            return RefreshTokenResult.failure("REFRESH_TOKEN_NOT_FOUND");
        }

        var storedToken = storedTokenOpt.get();

        if (storedToken.isRevoked()) {
            revokeAllForUser(storedToken.getUserId());
            return RefreshTokenResult.failure("REFRESH_TOKEN_REVOKED");
        }

        if (storedToken.isExpired()) {
            return RefreshTokenResult.failure("REFRESH_TOKEN_EXPIRED");
        }

        var userOpt = userRepository.findById(storedToken.getUserId());
        if (userOpt.isEmpty()) {
            return RefreshTokenResult.failure("USER_NOT_FOUND");
        }

        var user = userOpt.get();

        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        var newAccessToken = tokenProvider.generateAccessToken(user);
        var newRawRefreshToken = tokenProvider.generateRefreshToken();
        var expiry = Instant.now().plus(tokenProvider.getRefreshTokenExpiration());

        var newRefreshToken = RefreshToken.create(user.getId(), newRawRefreshToken, expiry);
        refreshTokenRepository.save(newRefreshToken);

        return RefreshTokenResult.success(newAccessToken, newRawRefreshToken);
    }

    private void revokeAllForUser(com.dsports.identity.domain.model.UserId userId) {
        refreshTokenRepository.revokeByUserId(userId);
    }
}
