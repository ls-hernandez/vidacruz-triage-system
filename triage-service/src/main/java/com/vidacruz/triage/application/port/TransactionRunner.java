package com.vidacruz.triage.application.port;

import reactor.core.publisher.Mono;

/**
 * Runs a reactive pipeline inside a single database transaction. The returned {@code Mono} completes only
 * after the commit, so anything chained after it observes committed data.
 */
public interface TransactionRunner {

	<T> Mono<T> inTransaction(Mono<T> work);

}
