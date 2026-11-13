package com.vidacruz.triage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.AuditEntry;
import com.vidacruz.triage.domain.InvalidStatusTransitionException;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.PatientEventType;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChangePatientStatusTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-11-13T09:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00.123456789Z");
	private static final Instant NOW_MICROS = Instant.parse("2026-11-13T10:00:00.123456Z");
	private static final UUID ID = UUID.randomUUID();

	private final List<String> calls = new ArrayList<>();
	private final List<AuditEntry> savedAudit = new ArrayList<>();
	private final List<PatientEvent> publishedEvents = new ArrayList<>();

	private Patient stored = patient(PatientStatus.REGISTERED, false, 0);
	/** Simulates what the database returns after the conditional update; {@code null} = no row matched. */
	private UnaryOperator<Patient> rowAfterUpdate = row -> row;
	private Mono<Void> publishResult = Mono.empty();

	private final PatientRepository patients = new PatientRepository() {
		@Override
		public Mono<Void> insert(Patient patient) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Mono<Patient> findById(UUID id) {
			return Mono.defer(() -> {
				calls.add("find");
				return Mono.justOrEmpty(stored);
			});
		}

		@Override
		public Mono<Patient> updateStatus(UUID id, PatientStatus expectedStatus, long expectedVersion,
				PatientStatus newStatus, Instant changedAt) {
			return Mono.defer(() -> {
				calls.add("update");
				if (stored.status() != expectedStatus || stored.version() != expectedVersion) {
					return Mono.empty();
				}
				Patient updated = new Patient(stored.id(), stored.fullName(), stored.age(), stored.vitalSigns(),
						stored.chiefComplaint(), stored.level(), stored.slaMinutes(), newStatus, stored.escalated(),
						stored.escalatedAt(), stored.registeredAt(), changedAt, stored.version() + 1);
				return Mono.justOrEmpty(rowAfterUpdate.apply(updated));
			});
		}

		@Override
		public Flux<UUID> findOverdueIds(Instant now) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Mono<Patient> markEscalated(UUID id, Instant escalatedAt) {
			throw new UnsupportedOperationException();
		}
	};

	private final AuditRepository audit = entry -> Mono.fromRunnable(() -> {
		calls.add("audit");
		savedAudit.add(entry);
	});

	private final TransactionRunner transactions = new TransactionRunner() {
		@Override
		public <T> Mono<T> inTransaction(Mono<T> work) {
			return work.doOnSuccess(ignored -> calls.add("commit"));
		}
	};

	private final PatientEventPublisher publisher = event -> Mono.defer(() -> {
		calls.add("publish");
		publishedEvents.add(event);
		return publishResult;
	});

	private final ChangePatientStatus changePatientStatus = new ChangePatientStatus(patients, audit, transactions,
			publisher, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void updatesAuditsAndPublishesAfterCommit() {
		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.IN_CONSULTATION))
			.assertNext(details -> {
				Patient updated = details.patient();
				assertThat(updated.status()).isEqualTo(PatientStatus.IN_CONSULTATION);
				assertThat(updated.statusChangedAt()).isEqualTo(NOW_MICROS);
				assertThat(updated.version()).isEqualTo(1);
				assertThat(details.timeInCurrentStatus()).isEqualTo(Duration.ZERO);

				assertThat(calls).containsExactly("find", "update", "audit", "commit", "publish");
				assertThat(savedAudit).containsExactly(new AuditEntry(ID, PatientStatus.REGISTERED,
						PatientStatus.IN_CONSULTATION, PatientEventType.PATIENT_IN_CONSULTATION, NOW_MICROS));
				assertThat(publishedEvents).singleElement().satisfies(event -> {
					assertThat(event.type()).isEqualTo(PatientEventType.PATIENT_IN_CONSULTATION);
					assertThat(event.patientId()).isEqualTo(ID);
					assertThat(event.level()).isEqualTo(TriageLevel.CRITICAL);
					assertThat(event.status()).isEqualTo(PatientStatus.IN_CONSULTATION);
					assertThat(event.occurredAt()).isEqualTo(NOW_MICROS);
				});
			})
			.verifyComplete();
	}

	@Test
	void dischargePublishesPatientDischarged() {
		stored = patient(PatientStatus.IN_CONSULTATION, false, 1);

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.DISCHARGED))
			.assertNext(details -> {
				assertThat(savedAudit).singleElement().satisfies(entry -> {
					assertThat(entry.previousStatus()).isEqualTo(PatientStatus.IN_CONSULTATION);
					assertThat(entry.newStatus()).isEqualTo(PatientStatus.DISCHARGED);
					assertThat(entry.eventType()).isEqualTo(PatientEventType.PATIENT_DISCHARGED);
				});
				assertThat(publishedEvents).singleElement()
					.extracting(PatientEvent::type)
					.isEqualTo(PatientEventType.PATIENT_DISCHARGED);
			})
			.verifyComplete();
	}

	@Test
	void usesTheRowReturnedByTheUpdateNotTheInMemoryCopy() {
		Instant escalatedAt = Instant.parse("2026-11-13T09:59:00Z");
		// an escalation (RF-03) committed between the read and the update
		rowAfterUpdate = row -> new Patient(row.id(), row.fullName(), row.age(), row.vitalSigns(), row.chiefComplaint(),
				row.level(), row.slaMinutes(), row.status(), true, escalatedAt, row.registeredAt(),
				row.statusChangedAt(), row.version());

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.IN_CONSULTATION))
			.assertNext(details -> {
				assertThat(details.patient().escalated()).isTrue();
				assertThat(details.patient().escalatedAt()).isEqualTo(escalatedAt);
			})
			.verifyComplete();
	}

	@Test
	void failsWithNotFoundWithoutWriting() {
		stored = null;

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.IN_CONSULTATION))
			.expectError(PatientNotFoundException.class)
			.verify();
		assertThat(calls).containsExactly("find");
	}

	@Test
	void rejectsInvalidTransitionWithoutWriting() {
		stored = patient(PatientStatus.DISCHARGED, false, 2);

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.IN_CONSULTATION))
			.expectError(InvalidStatusTransitionException.class)
			.verify();
		assertThat(calls).containsExactly("find");
		assertThat(savedAudit).isEmpty();
		assertThat(publishedEvents).isEmpty();
	}

	@Test
	void failsWithConcurrentUpdateWhenNoRowMatches() {
		rowAfterUpdate = row -> null;

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.DISCHARGED))
			.expectError(ConcurrentPatientUpdateException.class)
			.verify();
		assertThat(calls).containsExactly("find", "update");
		assertThat(savedAudit).isEmpty();
		assertThat(publishedEvents).isEmpty();
	}

	@Test
	void keepsTheChangeWhenPublishingFails() {
		publishResult = Mono.error(new IllegalStateException("Kafka is down"));

		StepVerifier.create(changePatientStatus.change(ID, PatientStatus.DISCHARGED))
			.assertNext(details -> assertThat(details.patient().status()).isEqualTo(PatientStatus.DISCHARGED))
			.verifyComplete();
		assertThat(calls).containsExactly("find", "update", "audit", "commit", "publish");
	}

	private static Patient patient(PatientStatus status, boolean escalated, long version) {
		VitalSigns vitalSigns = new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8);
		return new Patient(ID, "Ana Perez", 45, vitalSigns, "Dolor torácico", TriageLevel.CRITICAL, 5, status,
				escalated, escalated ? REGISTERED_AT : null, REGISTERED_AT, REGISTERED_AT, version);
	}

}
