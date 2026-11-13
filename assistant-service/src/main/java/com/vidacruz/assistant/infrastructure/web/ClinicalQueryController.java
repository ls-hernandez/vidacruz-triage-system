package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.application.AnswerClinicalQuestion;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class ClinicalQueryController {

	private final AnswerClinicalQuestion answerClinicalQuestion;

	ClinicalQueryController(AnswerClinicalQuestion answerClinicalQuestion) {
		this.answerClinicalQuestion = answerClinicalQuestion;
	}

	/** RF-04 A: answer from the protocol corpus with mandatory sources (RN-05). */
	@PostMapping("/asistente/consulta")
	Mono<ClinicalQueryResponse> query(@Valid @RequestBody ClinicalQueryRequest request) {
		return answerClinicalQuestion.answer(request.pregunta().strip()).map(ClinicalQueryResponse::from);
	}

}
