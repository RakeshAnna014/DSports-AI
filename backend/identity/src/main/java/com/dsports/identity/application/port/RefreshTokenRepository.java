package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.UserId;

import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByToken(String token);
    void save(RefreshToken refreshToken);
    void revokeByUserId(UserId userId);
}
