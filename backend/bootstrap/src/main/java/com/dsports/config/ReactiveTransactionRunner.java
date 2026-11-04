package com.dsports.config;

import com.dsports.shared.application.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/** R2DBC transaction for use cases; nested repository transactions join it (propagation REQUIRED). */
@Component
public class ReactiveTransactionRunner implements TransactionRunner {

    private final TransactionalOperator transactionalOperator;

    public ReactiveTransactionRunner(ReactiveTransactionManager transactionManager) {
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
        return Mono.defer(work).as(transactionalOperator::transactional);
    }
}
