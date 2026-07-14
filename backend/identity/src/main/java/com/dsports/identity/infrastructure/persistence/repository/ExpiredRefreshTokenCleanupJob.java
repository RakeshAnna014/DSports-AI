package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiredRefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public ExpiredRefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteExpired(Instant.now())
                .subscribe(count -> {
                    if (count > 0) {
                        log.info("Deleted {} expired refresh tokens", count);
                    }
                });
    }
}
