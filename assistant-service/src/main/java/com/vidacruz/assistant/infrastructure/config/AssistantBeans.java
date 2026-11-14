package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.application.AnswerClinicalQuestion;
import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.application.GetEmergencyStatus;
import com.vidacruz.assistant.application.NotifyEscalation;
import com.vidacruz.assistant.application.port.AlertNotifier;
import com.vidacruz.assistant.application.port.AnswerGenerator;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.application.port.ProtocolRetriever;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.infrastructure.alerts.AlertProperties;
import java.time.Clock;
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
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	NotifyEscalation notifyEscalation(AlertNotifier notifier, Clock clock, AlertProperties properties) {
		return new NotifyEscalation(notifier, clock, properties.maxAge());
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
