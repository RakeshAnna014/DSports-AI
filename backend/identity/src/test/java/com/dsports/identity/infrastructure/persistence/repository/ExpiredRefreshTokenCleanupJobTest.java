package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpiredRefreshTokenCleanupJobTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;

    private ExpiredRefreshTokenCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        cleanupJob = new ExpiredRefreshTokenCleanupJob(refreshTokenRepository);
    }

    @Test
    void shouldDeleteExpiredTokens() {
        when(refreshTokenRepository.deleteExpired(any())).thenReturn(Mono.just(5L));

        cleanupJob.deleteExpiredTokens();

        verify(refreshTokenRepository).deleteExpired(any());
    }
}
