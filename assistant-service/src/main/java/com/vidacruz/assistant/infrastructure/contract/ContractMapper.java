package com.vidacruz.assistant.infrastructure.contract;

import com.vidacruz.assistant.domain.PatientEventType;
import com.vidacruz.assistant.domain.TriageLevel;
import java.util.Optional;

/**
 * Maps the public contract values in Spanish to the assistant's internal (English) values and back. Used only at
 * the boundary: Kafka messages and HTTP DTOs. Mirrors the triage service's mapper on purpose, without sharing code.
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

	/** Empty for {@code null} or an unknown value. */
	public static Optional<TriageLevel> parseLevel(String level) {
		if (level == null) {
			return Optional.empty();
		}
		return switch (level) {
			case "CRITICO" -> Optional.of(TriageLevel.CRITICAL);
			case "URGENTE" -> Optional.of(TriageLevel.URGENT);
			case "URGENTE_2" -> Optional.of(TriageLevel.URGENT_2);
			case "NO_URGENTE" -> Optional.of(TriageLevel.NON_URGENT);
			case "NO_URGENTE_2" -> Optional.of(TriageLevel.NON_URGENT_2);
			default -> Optional.empty();
		};
	}

	/** Empty for {@code null} or an unknown value. */
	public static Optional<PatientEventType> parseEventType(String eventType) {
		if (eventType == null) {
			return Optional.empty();
		}
		return switch (eventType) {
			case "paciente_registrado" -> Optional.of(PatientEventType.PATIENT_REGISTERED);
			case "paciente_en_consulta" -> Optional.of(PatientEventType.PATIENT_IN_CONSULTATION);
			case "paciente_alta" -> Optional.of(PatientEventType.PATIENT_DISCHARGED);
			case "paciente_escalado" -> Optional.of(PatientEventType.PATIENT_ESCALATED);
			default -> Optional.empty();
		};
	}

}
