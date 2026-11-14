package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.infrastructure.contract.ContractMapper;
import java.time.Instant;
import java.util.UUID;

/** RF-06: {@code data} of an escalation alert on the SSE stream, in the Spanish contract. */
record EscalationAlertResponse(UUID pacienteId, String nivel, Instant fechaEscalamiento, String mensaje) {

	static EscalationAlertResponse from(EscalationAlert alert) {
		String level = ContractMapper.level(alert.level());
		return new EscalationAlertResponse(alert.patientId(), level, alert.escalatedAt(),
				"Paciente " + level + " superó su SLA sin ser atendido");
	}

}
