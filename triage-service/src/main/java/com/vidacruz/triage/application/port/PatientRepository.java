package com.vidacruz.triage.application.port;

import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientStatus;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface PatientRepository {

	Mono<Void> insert(Patient patient);

	/** Empty if the patient does not exist. */
	Mono<Patient> findById(UUID id);

	/**
	 * Atomically changes the status only if the patient is still in {@code expectedStatus} with
	 * {@code expectedVersion}; increments the version and sets {@code statusChangedAt}. No other column is touched,
	 * so a concurrent escalation is never overwritten.
	 *
	 * @return the persisted row after the change, or empty if nothing matched (concurrent modification)
	 */
	Mono<Patient> updateStatus(UUID id, PatientStatus expectedStatus, long expectedVersion, PatientStatus newStatus,
			Instant changedAt);

}
