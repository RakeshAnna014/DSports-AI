package com.dsports.jobs;

import com.dsports.ground.application.usecase.CompleteEndedBookingsUseCase;
import com.dsports.ground.application.usecase.ExpireHeldBookingsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically releases held ground slots whose payment window has passed (skipping any whose payment is being captured) and marks confirmed
 * bookings COMPLETED once they ended and the owner's no-show window passed.
 */
@Component
public class ExpireHeldBookingsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireHeldBookingsJob.class);

    private final ExpireHeldBookingsUseCase expireHeldBookingsUseCase;
    private final CompleteEndedBookingsUseCase completeEndedBookingsUseCase;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ExpireHeldBookingsJob(ExpireHeldBookingsUseCase expireHeldBookingsUseCase, CompleteEndedBookingsUseCase completeEndedBookingsUseCase) {
        this.expireHeldBookingsUseCase = expireHeldBookingsUseCase;
        this.completeEndedBookingsUseCase = completeEndedBookingsUseCase;
    }

    @Scheduled(fixedDelayString = "${app.ground.expiry-check-interval:15000}", initialDelayString = "${app.ground.expiry-initial-delay:30000}")
    public void expireHeldBookings() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        expireHeldBookingsUseCase.execute()
            .doOnNext(expired -> {
                if (expired > 0) {
                    log.info("Expired {} held booking(s) and released their slots", expired);
                }
            })
            .then(completeEndedBookingsUseCase.execute())
            .doFinally(signal -> running.set(false))
            .subscribe(
                completed -> {
                    if (completed > 0) {
                        log.info("Marked {} ended booking(s) completed", completed);
                    }
                },
                error -> log.error("Booking housekeeping run failed: {}", error.toString(), error));
    }
}
