package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.PatientDetails;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.VitalSigns;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * RF-02 body of {@code GET /triage/pacientes/{id}} and of the status change. Input fields keep the RF-01 request
 * names. {@code tiempoEnEstadoActual} is an ISO 8601 duration (e.g. {@code PT12M30S}) computed when read.
 */
public record PatientResponse(
		UUID id,
		String nombreCompleto,
		int edad,
		int frecuenciaCardiaca,
		int presionSistolica,
		int presionDiastolica,
		int spo2,
		BigDecimal temperatura,
		int dolor,
		int gcs,
		String quejaPrincipal,
		String nivel,
		int slaMinutos,
		String estado,
		Instant fechaRegistro,
		Instant fechaUltimoCambioEstado,
		boolean escalado,
		Instant fechaEscalamiento,
		String tiempoEnEstadoActual) {

	static PatientResponse from(PatientDetails details) {
		Patient p = details.patient();
		VitalSigns v = p.vitalSigns();
		return new PatientResponse(
				p.id(),
				p.fullName(),
				p.age(),
				v.heartRate(),
				v.systolicPressure(),
				v.diastolicPressure(),
				v.oxygenSaturation(),
				v.temperature(),
				v.painLevel(),
				v.glasgowScale(),
				p.chiefComplaint(),
				ContractMapper.level(p.level()),
				p.slaMinutes(),
				ContractMapper.status(p.status()),
				p.registeredAt(),
				p.statusChangedAt(),
				p.escalated(),
				p.escalatedAt(),
				details.timeInCurrentStatus().toString());
	}

}
