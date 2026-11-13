package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import java.time.Instant;
import java.util.UUID;

/** RF-01 response body (public contract in Spanish); {@code fechaRegistro} is ISO 8601 in UTC. */
public record PatientRegisteredResponse(UUID id, String nivel, int slaMinutos, String estado, Instant fechaRegistro) {

	static PatientRegisteredResponse from(Patient patient) {
		return new PatientRegisteredResponse(
				patient.id(),
				ContractMapper.level(patient.level()),
				patient.slaMinutes(),
				ContractMapper.status(patient.status()),
				patient.registeredAt());
	}

}
