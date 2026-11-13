package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.AuditEntry;
import com.vidacruz.triage.domain.InvalidStatusTransitionException;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.PatientStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * RF-02: changes the clinical status of a patient.
 * <p>
 * In one transaction: validate the transition against the current row, apply it with a conditional update (expected
 * status and version) and append the audit entry (RN-04). After the commit, publish {@code paciente_en_consulta} or
 * {@code paciente_alta}. If the conditional update matches no row, another request changed the patient first: nothing
 * is written or published.
 * <p>
 * The audit entry, the event and the result are built from the row returned by the update, not from the in-memory
 * copy, so a concurrent change of other columns (e.g. an escalation, RF-03) is reflected and never lost.
 */
public class ChangePatientStatus {

	private final PatientRepository patients;
	private final AuditRepository audit;
	private final TransactionRunner transactions;
	private final BestEffortEventPublication publication;
	private final Clock clock;

	public ChangePatientStatus(PatientRepository patients, AuditRepository audit, TransactionRunner transactions,
			PatientEventPublisher publisher, Clock clock) {
		this.patients = patients;
		this.audit = audit;
		this.transactions = transactions;
		this.publication = new BestEffortEventPublication(publisher);
		this.clock = clock;
	}

	/**
	 * Errors with {@link PatientNotFoundException}, {@link InvalidStatusTransitionException} or
	 * {@link ConcurrentPatientUpdateException}.
	 */
	public Mono<PatientDetails> change(UUID id, PatientStatus newStatus) {
		Mono<Patient> changeAndAudit = patients.findById(id)
			.switchIfEmpty(Mono.error(() -> new PatientNotFoundException(id)))
			.flatMap(current -> applyChange(current, newStatus));

		return transactions.inTransaction(changeAndAudit)
			.flatMap(updated -> publication.publish(PatientEvent.statusChanged(UUID.randomUUID(), updated))
				.thenReturn(PatientDetails.of(updated, clock)));
	}

	private Mono<Patient> applyChange(Patient current, PatientStatus newStatus) {
		try {
			current.requireCanChangeStatusTo(newStatus);
		}
		catch (InvalidStatusTransitionException ex) {
			return Mono.error(ex);
		}
		// timestamptz keeps microseconds: truncate so the response matches what is stored
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		return patients.updateStatus(current.id(), current.status(), current.version(), newStatus, now)
			.switchIfEmpty(Mono.error(() -> new ConcurrentPatientUpdateException(current.id())))
			.flatMap(updated -> audit.append(AuditEntry.statusChange(current.status(), updated)).thenReturn(updated));
	}

}
