package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.SlaPolicy;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.AuditEntry;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.TriageClassifier;
import com.vidacruz.triage.domain.TriageLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RF-01: classifies the patient (RN-01), captures its SLA (RN-02), stores the patient and its audit entry (RN-04)
 * in one transaction and, after the commit, publishes {@code paciente_registrado}.
 * <p>
 * If publishing fails the registration is kept and the error is logged: the event is lost (level 1 limitation,
 * solved by the Outbox pattern in level 2).
 */
public class RegisterPatient {

	private static final Logger log = LoggerFactory.getLogger(RegisterPatient.class);

	private final TriageClassifier classifier;
	private final SlaPolicy slaPolicy;
	private final PatientRepository patients;
	private final AuditRepository audit;
	private final TransactionRunner transactions;
	private final PatientEventPublisher publisher;
	private final Clock clock;

	public RegisterPatient(TriageClassifier classifier, SlaPolicy slaPolicy, PatientRepository patients,
			AuditRepository audit, TransactionRunner transactions, PatientEventPublisher publisher, Clock clock) {
		this.classifier = classifier;
		this.slaPolicy = slaPolicy;
		this.patients = patients;
		this.audit = audit;
		this.transactions = transactions;
		this.publisher = publisher;
		this.clock = clock;
	}

	public Mono<Patient> register(RegisterPatientCommand command) {
		return Mono.fromSupplier(() -> newPatient(command))
			.flatMap(patient -> transactions
				.inTransaction(patients.insert(patient).then(audit.append(AuditEntry.registration(patient))))
				.then(publishRegistered(patient))
				.thenReturn(patient));
	}

	private Patient newPatient(RegisterPatientCommand command) {
		TriageLevel level = classifier.classify(command.vitalSigns(), command.chiefComplaint());
		// timestamptz keeps microseconds: truncate so the response matches what is stored
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		return Patient.register(UUID.randomUUID(), command.fullName(), command.age(), command.vitalSigns(),
				command.chiefComplaint(), level, slaPolicy.slaMinutesFor(level), now);
	}

	private Mono<Void> publishRegistered(Patient patient) {
		PatientEvent event = PatientEvent.registered(UUID.randomUUID(), patient);
		return publisher.publish(event)
			.onErrorResume(error -> {
				log.error("Patient {} was registered but event {} ({}) could not be published; the event is lost",
						patient.id(), event.eventId(), event.type(), error);
				return Mono.empty();
			});
	}

}
