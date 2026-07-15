package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.UserId;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface RefreshTokenRepository {
    Mono<RefreshToken> findByToken(String token);
    Mono<Void> save(RefreshToken refreshToken);
    Mono<Void> revokeByUserId(UserId userId);
    Mono<Long> deleteExpired(Instant now);
}
