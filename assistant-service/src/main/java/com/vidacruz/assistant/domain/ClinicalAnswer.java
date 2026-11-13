package com.vidacruz.assistant.domain;

import java.util.List;

/**
 * Answer to a clinical question. Either clinical content backed by at least one corpus source, or the fixed
 * "no protocol" notice with no sources and no clinical content (RN-05).
 */
public record ClinicalAnswer(String answer, List<Source> sources) {

	public static final String NO_PROTOCOL_MESSAGE = "No existe un protocolo disponible en el corpus para esta pregunta.";

	public ClinicalAnswer {
		sources = List.copyOf(sources);
		if (sources.isEmpty() && !NO_PROTOCOL_MESSAGE.equals(answer)) {
			throw new IllegalArgumentException("A clinical answer must cite at least one source");
		}
	}

	public static ClinicalAnswer noProtocol() {
		return new ClinicalAnswer(NO_PROTOCOL_MESSAGE, List.of());
	}

}
