package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.AuditEntry;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RF-03: escalates the patients that exceeded their SLA (RN-02) without entering consultation.
 * <p>
 * Per patient, in one transaction: a conditional update sets the flag only if the patient is still
 * {@code REGISTERED} and not escalated, then the audit entry is appended (RN-04). After the commit,
 * {@code paciente_escalado} is published. If the update matches no row (already escalated, the patient entered
 * consultation, or another replica won the race) nothing is audited or published (RN-03).
 * <p>
 * A failure on one patient is logged and does not stop the others.
 */
public class EscalateOverduePatients {

	private static final Logger log = LoggerFactory.getLogger(EscalateOverduePatients.class);

	private final PatientRepository patients;
	private final AuditRepository audit;
	private final TransactionRunner transactions;
	private final BestEffortEventPublication publication;
	private final Clock clock;
	private final int concurrency;

	public EscalateOverduePatients(PatientRepository patients, AuditRepository audit, TransactionRunner transactions,
			PatientEventPublisher publisher, Clock clock, int concurrency) {
		if (concurrency < 1) {
			throw new IllegalArgumentException("concurrency must be positive");
		}
		this.patients = patients;
		this.audit = audit;
		this.transactions = transactions;
		this.publication = new BestEffortEventPublication(publisher);
		this.clock = clock;
		this.concurrency = concurrency;
	}

	/**
	 * One evaluation cycle. Errors only if the overdue patients cannot be listed.
	 *
	 * @return how many patients this call escalated
	 */
	public Mono<Long> escalateOverdue() {
		return Mono.defer(() -> {
			// timestamptz keeps microseconds: truncate so what is stored and published is the same instant
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			return patients.findOverdueIds(now)
				.flatMap(id -> escalate(id, now).onErrorResume(error -> {
					log.error("Could not escalate patient {}; it will be retried in the next cycle", id, error);
					return Mono.empty();
				}), concurrency)
				.count();
		});
	}

	/**
	 * Escalates one patient at {@code now}.
	 *
	 * @return the escalated row, or empty if this call did not escalate it
	 */
	public Mono<Patient> escalate(UUID id, Instant now) {
		Mono<Patient> escalateAndAudit = patients.markEscalated(id, now)
			.flatMap(escalated -> audit.append(AuditEntry.escalation(escalated)).thenReturn(escalated));

		return transactions.inTransaction(escalateAndAudit)
			.flatMap(escalated -> publication.publish(PatientEvent.escalated(UUID.randomUUID(), escalated))
				.thenReturn(escalated));
	}

}
