package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.RefreshTokenHasher;
import reactor.core.publisher.Mono;

public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;

    public LogoutUseCase(RefreshTokenRepository refreshTokenRepository, RefreshTokenHasher refreshTokenHasher) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
    }

    public Mono<Void> execute(LogoutCommand command) {
        var hashedToken = refreshTokenHasher.hash(command.refreshToken());

        return refreshTokenRepository.findByToken(hashedToken)
                .flatMap(token -> {
                    if (!token.belongsTo(command.userId())) {
                        return Mono.error(new IllegalArgumentException("Token does not belong to the authenticated user"));
                    }
                    token.revoke();
                    return refreshTokenRepository.save(token);
                })
                .then();
    }
}
