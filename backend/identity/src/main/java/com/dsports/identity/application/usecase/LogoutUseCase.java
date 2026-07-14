package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.TokenHasher;
import reactor.core.publisher.Mono;

public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenHasher tokenHasher;

    public LogoutUseCase(RefreshTokenRepository refreshTokenRepository, TokenHasher tokenHasher) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenHasher = tokenHasher;
    }

    public Mono<Void> execute(LogoutCommand command) {
        var hashedToken = tokenHasher.hash(command.refreshToken());

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
