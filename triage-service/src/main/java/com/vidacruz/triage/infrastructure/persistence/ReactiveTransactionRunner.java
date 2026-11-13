package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.port.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Reactive R2DBC transaction through the {@link TransactionalOperator} auto-configured by Spring Boot. */
@Component
class ReactiveTransactionRunner implements TransactionRunner {

	private final TransactionalOperator transactionalOperator;

	ReactiveTransactionRunner(TransactionalOperator transactionalOperator) {
		this.transactionalOperator = transactionalOperator;
	}

	@Override
	public <T> Mono<T> inTransaction(Mono<T> work) {
		return transactionalOperator.transactional(work);
	}

}
