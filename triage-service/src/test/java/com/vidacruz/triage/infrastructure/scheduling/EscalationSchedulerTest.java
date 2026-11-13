package com.vidacruz.triage.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.application.EscalateOverduePatients;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EscalationSchedulerTest {

	private final AtomicInteger cycles = new AtomicInteger();

	/** Every cycle fails listing the overdue patients (e.g. the database is down). */
	private final PatientRepository failingPatients = new PatientRepository() {
		@Override
		public Mono<Void> insert(Patient patient) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Mono<Patient> findById(UUID id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Mono<Patient> updateStatus(UUID id, PatientStatus expectedStatus, long expectedVersion,
				PatientStatus newStatus, Instant changedAt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Flux<UUID> findOverdueIds(Instant now) {
			return Flux.defer(() -> {
				cycles.incrementAndGet();
				return Flux.error(new IllegalStateException("database is down"));
			});
		}

		@Override
		public Mono<Patient> markEscalated(UUID id, Instant escalatedAt) {
			throw new UnsupportedOperationException();
		}
	};

	private final EscalationScheduler scheduler = new EscalationScheduler(new EscalateOverduePatients(
			failingPatients, entry -> Mono.empty(), new NoTransaction(), event -> Mono.empty(), Clock.systemUTC(), 1));

	@Test
	void aFailingCycleCompletesWithoutErrorAndTheNextOneRunsAgain() {
		StepVerifier.create(scheduler.run()).verifyComplete();
		StepVerifier.create(scheduler.run()).verifyComplete();

		assertThat(cycles).hasValue(2);
	}

	private static final class NoTransaction implements TransactionRunner {

		@Override
		public <T> Mono<T> inTransaction(Mono<T> work) {
			return work;
		}

	}

}
