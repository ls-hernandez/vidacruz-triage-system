package com.vidacruz.triage.application.port;

import com.vidacruz.triage.domain.Patient;
import reactor.core.publisher.Mono;

public interface PatientRepository {

	Mono<Void> insert(Patient patient);

}
