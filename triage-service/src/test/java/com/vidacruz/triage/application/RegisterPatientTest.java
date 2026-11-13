package com.vidacruz.triage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.AuditEntry;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.PatientEventType;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.domain.TriageClassifier;
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RegisterPatientTest {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00.123456789Z");
	private static final Instant NOW_MICROS = Instant.parse("2026-11-12T10:00:00.123456Z");

	/** Example E1: heart rate 130 → CRITICO. */
	private static final RegisterPatientCommand E1 = new RegisterPatientCommand("Ana Perez", 45,
			new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8), "Dolor torácico");

	private final List<String> calls = new ArrayList<>();
	private final List<Patient> savedPatients = new ArrayList<>();
	private final List<AuditEntry> savedAudit = new ArrayList<>();
	private final List<PatientEvent> publishedEvents = new ArrayList<>();

	private Mono<Void> publishResult = Mono.empty();
	private Mono<Void> insertResult = Mono.empty();

	private final PatientRepository patients = patient -> Mono.defer(() -> {
		calls.add("insert");
		savedPatients.add(patient);
		return insertResult;
	});

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

	private final RegisterPatient registerPatient = new RegisterPatient(new TriageClassifier(),
			level -> switch (level) {
				case CRITICAL -> 5;
				case URGENT -> 10;
				case URGENT_2 -> 30;
				case NON_URGENT -> 60;
				case NON_URGENT_2 -> 120;
			}, patients, audit, transactions, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void classifiesCapturesSlaAndUsesClockTruncatedToMicros() {
		StepVerifier.create(registerPatient.register(E1))
			.assertNext(patient -> {
				assertThat(patient.id()).isNotNull();
				assertThat(patient.level()).isEqualTo(TriageLevel.CRITICAL);
				assertThat(patient.slaMinutes()).isEqualTo(5);
				assertThat(patient.status()).isEqualTo(PatientStatus.REGISTERED);
				assertThat(patient.escalated()).isFalse();
				assertThat(patient.registeredAt()).isEqualTo(NOW_MICROS);
				assertThat(patient.statusChangedAt()).isEqualTo(NOW_MICROS);
				assertThat(savedPatients).containsExactly(patient);
			})
			.verifyComplete();
	}

	@Test
	void auditsRegistrationAndPublishesAfterCommit() {
		StepVerifier.create(registerPatient.register(E1))
			.assertNext(patient -> {
				assertThat(calls).containsExactly("insert", "audit", "commit", "publish");
				assertThat(savedAudit).containsExactly(new AuditEntry(patient.id(), null, PatientStatus.REGISTERED,
						PatientEventType.PATIENT_REGISTERED, NOW_MICROS));
				assertThat(publishedEvents).singleElement().satisfies(event -> {
					assertThat(event.eventId()).isNotNull();
					assertThat(event.type()).isEqualTo(PatientEventType.PATIENT_REGISTERED);
					assertThat(event.patientId()).isEqualTo(patient.id());
					assertThat(event.level()).isEqualTo(TriageLevel.CRITICAL);
					assertThat(event.status()).isEqualTo(PatientStatus.REGISTERED);
					assertThat(event.occurredAt()).isEqualTo(NOW_MICROS);
				});
			})
			.verifyComplete();
	}

	@Test
	void keepsRegistrationWhenPublishingFails() {
		publishResult = Mono.error(new IllegalStateException("Kafka is down"));

		StepVerifier.create(registerPatient.register(E1))
			.assertNext(patient -> assertThat(savedPatients).containsExactly(patient))
			.verifyComplete();
		assertThat(calls).containsExactly("insert", "audit", "commit", "publish");
	}

	@Test
	void doesNotPublishWhenTransactionFails() {
		insertResult = Mono.error(new IllegalStateException("database is down"));

		StepVerifier.create(registerPatient.register(E1))
			.expectErrorMessage("database is down")
			.verify();
		assertThat(calls).containsExactly("insert");
		assertThat(publishedEvents).isEmpty();
	}

}
