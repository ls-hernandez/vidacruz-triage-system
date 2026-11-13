package com.vidacruz.assistant.infrastructure.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Structured output requested from the model. Property names are in Spanish, like the prompt; the JSON schema sent to
 * the model is generated from this record.
 */
record ModelAnswer(
		@JsonProperty(value = "respuesta", required = true)
		@JsonPropertyDescription("Respuesta en español basada solo en los fragmentos, o exactamente SIN_PROTOCOLO")
		String answer,
		@JsonProperty(value = "fragmentos", required = true)
		@JsonPropertyDescription("Ids exactos de los fragmentos usados, tal como aparecen entre corchetes")
		List<String> fragmentIds) {
}
