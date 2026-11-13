package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.PatientRepository;
import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** RF-02: current data of a patient, with the time spent in its current status. */
public class GetPatient {

	private final PatientRepository patients;
	private final Clock clock;

	public GetPatient(PatientRepository patients, Clock clock) {
		this.patients = patients;
		this.clock = clock;
	}

	/** Errors with {@link PatientNotFoundException} if the patient does not exist. */
	public Mono<PatientDetails> get(UUID id) {
		return patients.findById(id)
			.switchIfEmpty(Mono.error(() -> new PatientNotFoundException(id)))
			.map(patient -> PatientDetails.of(patient, clock));
	}

}
