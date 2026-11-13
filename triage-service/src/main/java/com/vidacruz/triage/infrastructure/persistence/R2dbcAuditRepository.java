package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.domain.AuditEntry;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** Insert-only adapter: the table also rejects UPDATE/DELETE/TRUNCATE with a trigger (V2). */
@Repository
class R2dbcAuditRepository implements AuditRepository {

	private final DatabaseClient db;

	R2dbcAuditRepository(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<Void> append(AuditEntry entry) {
		GenericExecuteSpec spec = db.sql("""
				INSERT INTO audit_entries (patient_id, previous_status, new_status, event_type, occurred_at)
				VALUES (:patientId, :previousStatus, :newStatus, :eventType, :occurredAt)
				""")
			.bind("patientId", entry.patientId())
			.bind("newStatus", entry.newStatus().name())
			.bind("eventType", entry.eventType().name())
			.bind("occurredAt", entry.occurredAt());
		spec = entry.previousStatus() == null
				? spec.bindNull("previousStatus", String.class)
				: spec.bind("previousStatus", entry.previousStatus().name());
		return spec.then();
	}

}
