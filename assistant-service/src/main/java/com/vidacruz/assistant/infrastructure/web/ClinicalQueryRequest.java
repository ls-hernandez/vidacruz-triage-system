package com.vidacruz.assistant.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** RF-04 A request body: {@code {"pregunta": "..."}}. */
record ClinicalQueryRequest(
		@NotBlank(message = "es obligatoria y no puede estar vacía")
		@Size(max = 1000, message = "no puede superar 1000 caracteres")
		String pregunta) {
}
