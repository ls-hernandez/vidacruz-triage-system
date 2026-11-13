package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * RF-02 request body. Validated as a String so an unknown value is a 400 on {@code estado}, not a JSON error.
 */
public record ChangeStatusRequest(
		@NotNull(message = "es obligatorio")
		@Pattern(regexp = "REGISTRADO|EN_CONSULTA|ALTA", message = "debe ser uno de: REGISTRADO, EN_CONSULTA, ALTA")
		String estado) {

	PatientStatus toStatus() {
		return ContractMapper.parseStatus(estado);
	}

}
