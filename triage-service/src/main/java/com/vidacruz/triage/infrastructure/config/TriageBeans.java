package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.application.ChangePatientStatus;
import com.vidacruz.triage.application.EscalateOverduePatients;
import com.vidacruz.triage.application.GetOperationReport;
import com.vidacruz.triage.application.GetPatient;
import com.vidacruz.triage.application.RegisterPatient;
import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.OperationReportRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.SlaPolicy;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.TriageClassifier;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free domain and application classes. */
@Configuration(proxyBeanMethods = false)
public class TriageBeans {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	TriageClassifier triageClassifier() {
		return new TriageClassifier();
	}

	@Bean
	RegisterPatient registerPatient(TriageClassifier classifier, SlaPolicy slaPolicy, PatientRepository patients,
			AuditRepository audit, TransactionRunner transactions, PatientEventPublisher publisher, Clock clock) {
		return new RegisterPatient(classifier, slaPolicy, patients, audit, transactions, publisher, clock);
	}

	@Bean
	GetPatient getPatient(PatientRepository patients, Clock clock) {
		return new GetPatient(patients, clock);
	}

	@Bean
	GetOperationReport getOperationReport(OperationReportRepository reports) {
		return new GetOperationReport(reports);
	}

	@Bean
	ChangePatientStatus changePatientStatus(PatientRepository patients, AuditRepository audit,
			TransactionRunner transactions, PatientEventPublisher publisher, Clock clock) {
		return new ChangePatientStatus(patients, audit, transactions, publisher, clock);
	}

	@Bean
	EscalateOverduePatients escalateOverduePatients(PatientRepository patients, AuditRepository audit,
			TransactionRunner transactions, PatientEventPublisher publisher, Clock clock,
			EscalationProperties properties) {
		return new EscalateOverduePatients(patients, audit, transactions, publisher, clock, properties.concurrency());
	}

}
