package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.ChangePatientStatus;
import com.vidacruz.triage.application.GetPatient;
import com.vidacruz.triage.application.RegisterPatient;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
	private final GetPatient getPatient;
	private final ChangePatientStatus changePatientStatus;

	PatientController(RegisterPatient registerPatient, GetPatient getPatient, ChangePatientStatus changePatientStatus) {
		this.registerPatient = registerPatient;
		this.getPatient = getPatient;
		this.changePatientStatus = changePatientStatus;
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

	/** RF-02: current data of a patient. */
	@GetMapping("/{id}")
	Mono<PatientResponse> get(@PathVariable("id") UUID id) {
		return getPatient.get(id).map(PatientResponse::from);
	}

	/** RF-02: clinical status change; responds with the same body as the GET. */
	@PatchMapping("/{id}/estado")
	Mono<PatientResponse> changeStatus(@PathVariable("id") UUID id, @Valid @RequestBody ChangeStatusRequest request) {
		return changePatientStatus.change(id, request.toStatus()).map(PatientResponse::from);
	}

}
