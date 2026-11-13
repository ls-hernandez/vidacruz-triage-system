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
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * RF-03 with a fixed {@link Clock} and an in-memory repository that applies the same rules as the SQL: overdue
 * query ({@link Patient#isOverdueAt}) and conditional escalation update.
 */
class EscalateOverduePatientsTest {

	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00.123456789Z");
	private static final Instant NOW_MICROS = Instant.parse("2026-11-13T10:00:00.123456Z");
	private static final int SLA_MINUTES = 5;
	private static final int CONCURRENCY = 2;

	private final Map<UUID, Patient> rows = new ConcurrentHashMap<>();
	private final List<String> calls = new CopyOnWriteArrayList<>();
	private final List<AuditEntry> savedAudit = new CopyOnWriteArrayList<>();
	private final List<PatientEvent> publishedEvents = new CopyOnWriteArrayList<>();

	private final Set<UUID> failingIds = ConcurrentHashMap.newKeySet();
	private Duration updateLatency = Duration.ZERO;
	private Mono<Void> publishResult = Mono.empty();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger maxInFlight = new AtomicInteger();

	private final PatientRepository patients = new PatientRepository() {
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
			return Flux.defer(() -> Flux.fromStream(rows.values()
				.stream()
				.filter(patient -> patient.isOverdueAt(now))
				.sorted(Comparator.comparing(Patient::registeredAt))
				.map(Patient::id)));
		}

		@Override
		public Mono<Patient> markEscalated(UUID id, Instant escalatedAt) {
			Mono<Patient> update = Mono.defer(() -> {
				calls.add("update");
				if (failingIds.contains(id)) {
					return Mono.error(new IllegalStateException("database error"));
				}
				// same guard as the SQL WHERE, applied atomically on the row
				Patient[] updated = new Patient[1];
				rows.computeIfPresent(id, (key, row) -> {
					if (row.status() != PatientStatus.REGISTERED || row.escalated()) {
						return row;
					}
					updated[0] = escalated(row, escalatedAt);
					return updated[0];
				});
				return Mono.justOrEmpty(updated[0]);
			});
			Mono<Patient> delayed = updateLatency.isZero() ? update : Mono.delay(updateLatency).then(update);
			return delayed
				.doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
				// doOnTerminate runs before the signal reaches flatMap (doFinally would run after the next subscribe)
				.doOnTerminate(inFlight::decrementAndGet);
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

	private final EscalateOverduePatients useCase = new EscalateOverduePatients(patients, audit, transactions,
			publisher, Clock.fixed(NOW, ZoneOffset.UTC), CONCURRENCY);

	@Test
	void escalatesAnOverduePatientOnceAuditingAndPublishingAfterCommit() {
		UUID id = store(registeredSlaAgo(Duration.ofSeconds(1)));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(1L).verifyComplete();

		Patient row = rows.get(id);
		assertThat(row.escalated()).isTrue();
		assertThat(row.escalatedAt()).isEqualTo(NOW_MICROS);
		assertThat(row.status()).isEqualTo(PatientStatus.REGISTERED);
		assertThat(row.version()).isZero();
		assertThat(calls).containsExactly("update", "audit", "commit", "publish");
		assertThat(savedAudit).containsExactly(new AuditEntry(id, PatientStatus.REGISTERED, PatientStatus.REGISTERED,
				PatientEventType.PATIENT_ESCALATED, NOW_MICROS));
		assertThat(publishedEvents).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(PatientEventType.PATIENT_ESCALATED);
			assertThat(event.patientId()).isEqualTo(id);
			assertThat(event.level()).isEqualTo(TriageLevel.CRITICAL);
			assertThat(event.status()).isEqualTo(PatientStatus.REGISTERED);
			assertThat(event.occurredAt()).isEqualTo(NOW_MICROS);
		});
	}

	@Test
	void doesNotEscalateExactlyAtTheSlaDeadline() {
		UUID id = store(registeredSlaAgo(Duration.ZERO));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(0L).verifyComplete();

		assertThat(rows.get(id).escalated()).isFalse();
		assertThat(calls).isEmpty();
	}

	@Test
	void doesNotEscalateBeforeTheSla() {
		UUID id = store(registeredSlaAgo(Duration.ofSeconds(-1)));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(0L).verifyComplete();

		assertThat(rows.get(id).escalated()).isFalse();
		assertThat(calls).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(value = PatientStatus.class, names = { "IN_CONSULTATION", "DISCHARGED" })
	void neverEscalatesOutsideRegistered(PatientStatus status) {
		UUID id = store(withStatus(registeredSlaAgo(Duration.ofHours(1)), status));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(0L).verifyComplete();
		StepVerifier.create(useCase.escalate(id, NOW_MICROS)).verifyComplete();

		assertThat(rows.get(id).escalated()).isFalse();
		assertThat(savedAudit).isEmpty();
		assertThat(publishedEvents).isEmpty();
	}

	@Test
	void secondRunDoesNotDuplicateAuditNorEvent() {
		store(registeredSlaAgo(Duration.ofMinutes(1)));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(1L).verifyComplete();
		StepVerifier.create(useCase.escalateOverdue()).expectNext(0L).verifyComplete();

		assertThat(savedAudit).hasSize(1);
		assertThat(publishedEvents).hasSize(1);
	}

	@Test
	void doesNothingWhenTheConditionalUpdateMatchesNoRow() {
		// the patient was listed as overdue but another replica escalated it before this update
		UUID id = store(escalated(registeredSlaAgo(Duration.ofMinutes(1)), NOW_MICROS.minusSeconds(1)));

		StepVerifier.create(useCase.escalate(id, NOW_MICROS)).verifyComplete();

		// the (empty) transaction commits, but nothing is audited or published
		assertThat(calls).containsExactly("update", "commit");
		assertThat(savedAudit).isEmpty();
		assertThat(publishedEvents).isEmpty();
	}

	@Test
	void aFailingPatientDoesNotStopTheOthers() {
		UUID failing = store(registeredSlaAgo(Duration.ofMinutes(3)));
		UUID ok = store(registeredSlaAgo(Duration.ofMinutes(1)));
		failingIds.add(failing);

		StepVerifier.create(useCase.escalateOverdue()).expectNext(1L).verifyComplete();

		assertThat(rows.get(failing).escalated()).isFalse();
		assertThat(rows.get(ok).escalated()).isTrue();
		assertThat(publishedEvents).extracting(PatientEvent::patientId).containsExactly(ok);

		// the failed patient is still overdue and is retried in the next cycle
		failingIds.clear();
		StepVerifier.create(useCase.escalateOverdue()).expectNext(1L).verifyComplete();
		assertThat(rows.get(failing).escalated()).isTrue();
	}

	@Test
	void keepsTheEscalationWhenPublishingFails() {
		UUID id = store(registeredSlaAgo(Duration.ofMinutes(1)));
		publishResult = Mono.error(new IllegalStateException("Kafka is down"));

		StepVerifier.create(useCase.escalateOverdue()).expectNext(1L).verifyComplete();

		assertThat(rows.get(id).escalated()).isTrue();
		assertThat(savedAudit).hasSize(1);
		assertThat(calls).containsExactly("update", "audit", "commit", "publish");
	}

	@Test
	void boundsTheNumberOfPatientsEscalatedInParallel() {
		for (int i = 0; i < 6; i++) {
			store(registeredSlaAgo(Duration.ofMinutes(i + 1)));
		}
		updateLatency = Duration.ofMillis(50);

		StepVerifier.create(useCase.escalateOverdue()).expectNext(6L).verifyComplete();

		assertThat(maxInFlight.get()).isEqualTo(CONCURRENCY);
		assertThat(publishedEvents).hasSize(6);
	}

	// --- helpers ----------------------------------------------------------------------------------------------

	private UUID store(Patient patient) {
		rows.put(patient.id(), patient);
		return patient.id();
	}

	/** REGISTERED patient whose SLA deadline was {@code overdueBy} before NOW (negative = still within the SLA). */
	private static Patient registeredSlaAgo(Duration overdueBy) {
		Instant registeredAt = NOW_MICROS.minus(Duration.ofMinutes(SLA_MINUTES)).minus(overdueBy);
		VitalSigns vitalSigns = new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8);
		return Patient.register(UUID.randomUUID(), "Ana Perez", 45, vitalSigns, "Dolor torácico",
				TriageLevel.CRITICAL, SLA_MINUTES, registeredAt);
	}

	private static Patient withStatus(Patient p, PatientStatus status) {
		return new Patient(p.id(), p.fullName(), p.age(), p.vitalSigns(), p.chiefComplaint(), p.level(),
				p.slaMinutes(), status, p.escalated(), p.escalatedAt(), p.registeredAt(), p.statusChangedAt(),
				p.version() + 1);
	}

	private static Patient escalated(Patient p, Instant escalatedAt) {
		return new Patient(p.id(), p.fullName(), p.age(), p.vitalSigns(), p.chiefComplaint(), p.level(),
				p.slaMinutes(), p.status(), true, escalatedAt, p.registeredAt(), p.statusChangedAt(), p.version());
	}

}
