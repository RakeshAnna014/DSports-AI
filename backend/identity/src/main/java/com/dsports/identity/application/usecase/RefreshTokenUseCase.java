package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenHasher;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.RefreshFailureReason;
import com.dsports.identity.application.result.RefreshTokenResult;
import com.dsports.identity.domain.model.RefreshToken;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class RefreshTokenUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;
    private final TokenHasher tokenHasher;

    public RefreshTokenUseCase(RefreshTokenRepository refreshTokenRepository,
                                UserRepository userRepository,
                                TokenProvider tokenProvider,
                                TokenHasher tokenHasher) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.tokenHasher = tokenHasher;
    }

    public Mono<RefreshTokenResult> execute(RefreshTokenCommand command) {
        var hashedToken = tokenHasher.hash(command.refreshToken());

        return refreshTokenRepository.findByToken(hashedToken)
                .flatMap(storedToken -> {
                    if (storedToken.isRevoked()) {
                        return revokeAllForUser(storedToken.getUserId())
                                .thenReturn(RefreshTokenResult.failure(RefreshFailureReason.TOKEN_REVOKED));
                    }

                    if (storedToken.isExpired()) {
                        return Mono.just(RefreshTokenResult.failure(RefreshFailureReason.TOKEN_EXPIRED));
                    }

                    return userRepository.findById(storedToken.getUserId())
                            .flatMap(user -> {
                                if (user.getStatus().isDeleted()) {
                                    return Mono.just(RefreshTokenResult.failure(RefreshFailureReason.USER_DELETED));
                                }

                                if (user.getStatus().isDisabled()) {
                                    return Mono.just(RefreshTokenResult.failure(RefreshFailureReason.USER_DISABLED));
                                }

                                if (!user.canLogin()) {
                                    return Mono.just(RefreshTokenResult.failure(RefreshFailureReason.USER_LOCKED));
                                }

                                storedToken.revoke();

                                var newAccessToken = tokenProvider.generateAccessToken(user);
                                var newRawRefreshToken = tokenProvider.generateRefreshToken();
                                var newHashedToken = tokenHasher.hash(newRawRefreshToken);
                                var expiry = Instant.now().plus(tokenProvider.getRefreshTokenExpiration());

                                var newRefreshToken = RefreshToken.create(
                                        user.getId(), newHashedToken, expiry,
                                        storedToken.getDeviceName(), storedToken.getUserAgent(),
                                        storedToken.getIpAddress());

                                return refreshTokenRepository.save(storedToken)
                                        .then(refreshTokenRepository.save(newRefreshToken))
                                        .thenReturn(RefreshTokenResult.success(newAccessToken, newRawRefreshToken));
                            })
                            .switchIfEmpty(Mono.just(RefreshTokenResult.failure(RefreshFailureReason.USER_NOT_FOUND)));
                })
                .switchIfEmpty(Mono.just(RefreshTokenResult.failure(RefreshFailureReason.TOKEN_NOT_FOUND)));
    }

    private Mono<Void> revokeAllForUser(com.dsports.identity.domain.model.UserId userId) {
        return refreshTokenRepository.revokeByUserId(userId);
    }
}
