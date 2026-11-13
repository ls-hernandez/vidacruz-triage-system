package com.vidacruz.triage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetPatientTest {

	private static final UUID ID = UUID.randomUUID();
	private static final Instant STATUS_CHANGED_AT = Instant.parse("2026-11-13T10:00:00Z");

	private Patient stored;

	private final PatientRepository patients = new PatientRepository() {
		@Override
		public Mono<Void> insert(Patient patient) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Mono<Patient> findById(UUID id) {
			return Mono.justOrEmpty(stored);
		}

		@Override
		public Mono<Patient> updateStatus(UUID id, PatientStatus expectedStatus, long expectedVersion,
				PatientStatus newStatus, Instant changedAt) {
			throw new UnsupportedOperationException();
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

	private GetPatient at(Instant now) {
		return new GetPatient(patients, Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void computesTimeInCurrentStatusTruncatedToSeconds() {
		stored = patient(STATUS_CHANGED_AT);

		StepVerifier.create(at(Instant.parse("2026-11-13T10:12:30.987Z")).get(ID))
			.assertNext(details -> {
				assertThat(details.patient()).isEqualTo(stored);
				assertThat(details.timeInCurrentStatus()).isEqualTo(Duration.parse("PT12M30S"));
				assertThat(details.timeInCurrentStatus().toString()).isEqualTo("PT12M30S");
			})
			.verifyComplete();
	}

	@Test
	void neverReturnsNegativeTimeWithClockSkew() {
		stored = patient(STATUS_CHANGED_AT);

		StepVerifier.create(at(STATUS_CHANGED_AT.minusSeconds(3)).get(ID))
			.assertNext(details -> assertThat(details.timeInCurrentStatus().toString()).isEqualTo("PT0S"))
			.verifyComplete();
	}

	@Test
	void failsWithNotFound() {
		stored = null;

		StepVerifier.create(at(STATUS_CHANGED_AT).get(ID))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(PatientNotFoundException.class,
					ex -> assertThat(ex.patientId()).isEqualTo(ID)))
			.verify();
	}

	private static Patient patient(Instant statusChangedAt) {
		VitalSigns vitalSigns = new VitalSigns(72, 120, 80, 99, new BigDecimal("36.5"), 15, 2);
		return new Patient(ID, "Ana Perez", 45, vitalSigns, "Herida por cortar", TriageLevel.NON_URGENT, 60,
				PatientStatus.IN_CONSULTATION, false, null, statusChangedAt.minusSeconds(600), statusChangedAt, 1);
	}

}
