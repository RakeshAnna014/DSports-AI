package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.port.RefreshTokenRepository;

public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutUseCase(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public void execute(LogoutCommand command) {
        var tokenOpt = refreshTokenRepository.findByToken(command.refreshToken());
        tokenOpt.ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }
}
