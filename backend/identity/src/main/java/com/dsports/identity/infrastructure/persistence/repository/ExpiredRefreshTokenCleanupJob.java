package com.dsports.identity.infrastructure.persistence.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpiredRefreshTokenCleanupJob {

    private final DatabaseClient databaseClient;

    public ExpiredRefreshTokenCleanupJob(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void deleteExpiredTokens() {
        databaseClient.sql("DELETE FROM refresh_tokens WHERE expires_at < NOW()")
                .then()
                .subscribe();
    }
}
