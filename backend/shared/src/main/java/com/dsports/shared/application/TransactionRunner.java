package com.dsports.shared.application;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Runs a unit of work in a single local database transaction.
 * <p>
 * Use cases stay framework-free; the composition root provides the reactive implementation.
 * Repository adapters that open their own transactions join the surrounding one.
 */
public interface TransactionRunner {

    <T> Mono<T> inTransaction(Supplier<Mono<T>> work);
}
