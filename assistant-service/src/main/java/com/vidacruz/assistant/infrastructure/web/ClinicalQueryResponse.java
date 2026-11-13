package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.domain.ClinicalAnswer;
import java.util.List;

/** RF-04 A response: {@code {"respuesta": "...", "fuentes": [{"protocolo": "PROTO-XXX", "seccion": "..."}]}}. */
record ClinicalQueryResponse(String respuesta, List<SourceResponse> fuentes) {

	record SourceResponse(String protocolo, String seccion) {
	}

	static ClinicalQueryResponse from(ClinicalAnswer answer) {
		return new ClinicalQueryResponse(answer.answer(),
				answer.sources().stream().map(s -> new SourceResponse(s.protocol(), s.section())).toList());
	}

}
