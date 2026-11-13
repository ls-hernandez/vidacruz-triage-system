package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.application.AnswerClinicalQuestion;
import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.application.GetEmergencyStatus;
import com.vidacruz.assistant.application.port.AnswerGenerator;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.application.port.ProtocolRetriever;
import com.vidacruz.assistant.domain.Corpus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application classes. */
@Configuration(proxyBeanMethods = false)
public class AssistantBeans {

	@Bean
	ApplyPatientEvent applyPatientEvent(EmergencyStatusView view) {
		return new ApplyPatientEvent(view);
	}

	@Bean
	GetEmergencyStatus getEmergencyStatus(EmergencyStatusView view) {
		return new GetEmergencyStatus(view);
	}

	@Bean
	AnswerClinicalQuestion answerClinicalQuestion(ProtocolRetriever retriever, AnswerGenerator generator,
			Corpus corpus) {
		return new AnswerClinicalQuestion(retriever, generator, corpus);
	}

}
