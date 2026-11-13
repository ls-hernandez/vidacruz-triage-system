package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.RegisterPatient;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/triage/pacientes")
class PatientController {

	private final RegisterPatient registerPatient;

	PatientController(RegisterPatient registerPatient) {
		this.registerPatient = registerPatient;
	}

	/** RF-01: registers and classifies a patient. */
	@PostMapping
	Mono<ResponseEntity<PatientRegisteredResponse>> register(@Valid @RequestBody RegisterPatientRequest request,
			UriComponentsBuilder uriBuilder) {
		return registerPatient.register(request.toCommand())
			.map(patient -> ResponseEntity
				.created(uriBuilder.path("/triage/pacientes/{id}").buildAndExpand(patient.id()).toUri())
				.body(PatientRegisteredResponse.from(patient)));
	}

}
