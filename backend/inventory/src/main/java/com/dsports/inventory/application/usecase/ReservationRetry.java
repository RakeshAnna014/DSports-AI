package com.dsports.inventory.application.usecase;

import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;

/**
 * Concurrent writers to the same inventory row are serialised by its {@code @Version} column: the loser of a race
 * gets an optimistic-locking conflict and re-reads the current stock before trying again.
 */
final class ReservationRetry {

    private static final int MAX_ATTEMPTS = 5;

    private ReservationRetry() {
    }

    static RetryBackoffSpec onOptimisticConflict() {
        return Retry.backoff(MAX_ATTEMPTS, Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(200))
                .jitter(0.5)
                .filter(ReservationRetry::isOptimisticConflict)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isOptimisticConflict(Throwable error) {
        return error instanceof InventoryDomainException ide
                && ide.getErrorCode() == InventoryErrorCode.OPTIMISTIC_LOCKING_CONFLICT;
    }
}
