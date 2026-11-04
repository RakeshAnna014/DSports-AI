package com.dsports.jobs;

import com.dsports.order.application.order.usecase.ExpireUnpaidOrdersUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically cancels unpaid orders whose payment window has passed, returning their reserved stock. */
@Component
public class ExpireUnpaidOrdersJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireUnpaidOrdersJob.class);

    private final ExpireUnpaidOrdersUseCase expireUnpaidOrdersUseCase;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ExpireUnpaidOrdersJob(ExpireUnpaidOrdersUseCase expireUnpaidOrdersUseCase) {
        this.expireUnpaidOrdersUseCase = expireUnpaidOrdersUseCase;
    }

    @Scheduled(fixedDelayString = "${app.orders.expiry-check-interval:60000}", initialDelayString = "${app.orders.expiry-initial-delay:30000}")
    public void expireUnpaidOrders() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        expireUnpaidOrdersUseCase.execute()
            .doFinally(signal -> running.set(false))
            .subscribe(
                expired -> {
                    if (expired > 0) {
                        log.info("Expired {} unpaid order(s) and released their stock", expired);
                    }
                },
                error -> log.error("Unpaid-order expiry run failed: {}", error.toString(), error));
    }
}
