package com.vidacruz.triage.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies the Flyway schema against a real PostgreSQL: constraints that back the business rules
 * and the append-only audit trail (RN-04).
 */
@SpringBootTest(properties = "triage.escalation.enabled=false")
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	@Autowired
	private DatabaseClient db;

	@Test
	void acceptsValidPatientAndAuditEntry() {
		UUID id = UUID.randomUUID();

		StepVerifier.create(insertPatient(id, p -> { }).then(insertRegistrationAudit(id)))
			.expectNext(1L)
			.verifyComplete();

		StepVerifier.create(db.sql("SELECT temperature, escalated, version FROM patients WHERE id = :id")
				.bind("id", id)
				.map(row -> Map.of(
						"temperature", row.get("temperature", BigDecimal.class),
						"escalated", row.get("escalated", Boolean.class),
						"version", row.get("version", Long.class)))
				.one())
			.assertNext(row -> {
				assertThat(row.get("temperature")).isEqualTo(new BigDecimal("36.8"));
				assertThat(row.get("escalated")).isEqualTo(false);
				assertThat(row.get("version")).isEqualTo(0L);
			})
			.verifyComplete();
	}

	@Test
	void rejectsDiastolicNotLowerThanSystolic() {
		StepVerifier.create(insertPatient(UUID.randomUUID(), p -> {
				p.put("systolic", 120);
				p.put("diastolic", 120);
			}))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("chk_patients_blood_pressure"))
			.verify();
	}

	@Test
	void rejectsUnknownLevel() {
		StepVerifier.create(insertPatient(UUID.randomUUID(), p -> p.put("level", "CRITICO")))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("chk_patients_level"))
			.verify();
	}

	@Test
	void rejectsUnknownStatus() {
		StepVerifier.create(insertPatient(UUID.randomUUID(), p -> p.put("status", "REGISTRADO")))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("chk_patients_status"))
			.verify();
	}

	@Test
	void rejectsEscalatedFlagWithoutTimestamp() {
		UUID id = UUID.randomUUID();

		StepVerifier.create(insertPatient(id, p -> { })
				.then(db.sql("UPDATE patients SET escalated = true WHERE id = :id").bind("id", id).fetch().rowsUpdated()))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("chk_patients_escalated_at"))
			.verify();
	}

	@Test
	void rejectsUpdateOfAuditEntry() {
		UUID id = UUID.randomUUID();

		StepVerifier.create(insertPatient(id, p -> { })
				.then(insertRegistrationAudit(id))
				.then(db.sql("UPDATE audit_entries SET new_status = 'DISCHARGED' WHERE patient_id = :id")
					.bind("id", id)
					.fetch()
					.rowsUpdated()))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("append-only: UPDATE"))
			.verify();
	}

	@Test
	void rejectsDeleteOfAuditEntry() {
		UUID id = UUID.randomUUID();

		StepVerifier.create(insertPatient(id, p -> { })
				.then(insertRegistrationAudit(id))
				.then(db.sql("DELETE FROM audit_entries WHERE patient_id = :id").bind("id", id).fetch().rowsUpdated()))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("append-only: DELETE"))
			.verify();
	}

	@Test
	void rejectsTruncateOfAuditEntries() {
		StepVerifier.create(db.sql("TRUNCATE audit_entries").fetch().rowsUpdated())
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("append-only: TRUNCATE"))
			.verify();
	}

	/** Inserts a valid patient (example E1); {@code overrides} replaces individual parameters. */
	private Mono<Long> insertPatient(UUID id, Consumer<Map<String, Object>> overrides) {
		Map<String, Object> p = new HashMap<>();
		p.put("systolic", 180);
		p.put("diastolic", 110);
		p.put("level", "CRITICAL");
		p.put("status", "REGISTERED");
		overrides.accept(p);

		return db.sql("""
				INSERT INTO patients (id, full_name, age, heart_rate, systolic_bp, diastolic_bp, spo2, temperature,
				                      pain_level, gcs, chief_complaint, level, sla_minutes, status,
				                      registered_at, status_changed_at)
				VALUES (:id, 'Ana Perez', 45, 130, :systolic, :diastolic, 97, :temperature,
				        8, 15, 'Dolor toracico', :level, 5, :status, :now, :now)
				""")
			.bind("id", id)
			.bind("systolic", p.get("systolic"))
			.bind("diastolic", p.get("diastolic"))
			.bind("temperature", new BigDecimal("36.8"))
			.bind("level", p.get("level"))
			.bind("status", p.get("status"))
			.bind("now", NOW)
			.fetch()
			.rowsUpdated();
	}

	private Mono<Long> insertRegistrationAudit(UUID patientId) {
		return db.sql("""
				INSERT INTO audit_entries (patient_id, previous_status, new_status, event_type, occurred_at)
				VALUES (:patientId, NULL, 'REGISTERED', 'PATIENT_REGISTERED', :now)
				""")
			.bind("patientId", patientId)
			.bind("now", NOW)
			.fetch()
			.rowsUpdated();
	}

}
