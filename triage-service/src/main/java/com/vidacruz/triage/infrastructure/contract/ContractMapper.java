package com.vidacruz.triage.infrastructure.contract;

import com.vidacruz.triage.domain.PatientEventType;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.domain.TriageLevel;

/**
 * Maps internal (English) values to the public contract values in Spanish. Used only at the boundary:
 * HTTP DTOs and Kafka messages.
 */
public final class ContractMapper {

	private ContractMapper() {
	}

	public static String level(TriageLevel level) {
		return switch (level) {
			case CRITICAL -> "CRITICO";
			case URGENT -> "URGENTE";
			case URGENT_2 -> "URGENTE_2";
			case NON_URGENT -> "NO_URGENTE";
			case NON_URGENT_2 -> "NO_URGENTE_2";
		};
	}

	public static String status(PatientStatus status) {
		return switch (status) {
			case REGISTERED -> "REGISTRADO";
			case IN_CONSULTATION -> "EN_CONSULTA";
			case DISCHARGED -> "ALTA";
		};
	}

	/** Inverse of {@link #status(PatientStatus)}; the input must already be validated as a contract value. */
	public static PatientStatus parseStatus(String status) {
		return switch (status) {
			case "REGISTRADO" -> PatientStatus.REGISTERED;
			case "EN_CONSULTA" -> PatientStatus.IN_CONSULTATION;
			case "ALTA" -> PatientStatus.DISCHARGED;
			default -> throw new IllegalArgumentException("Unknown patient status: " + status);
		};
	}

	public static String eventType(PatientEventType type) {
		return switch (type) {
			case PATIENT_REGISTERED -> "paciente_registrado";
			case PATIENT_IN_CONSULTATION -> "paciente_en_consulta";
			case PATIENT_DISCHARGED -> "paciente_alta";
			case PATIENT_ESCALATED -> "paciente_escalado";
		};
	}

}
