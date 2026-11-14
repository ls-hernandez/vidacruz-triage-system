package com.vidacruz.assistant.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** SSE framing of the alert stream: event fields and heartbeat, with virtual time. */
class EscalationAlertControllerTest {

	private static final Duration HEARTBEAT = Duration.ofSeconds(15);

	@Test
	void sendsAHeartbeatRightAwayAndThenEveryInterval() {
		StepVerifier.withVirtualTime(() -> EscalationAlertController.events(Flux.never(), HEARTBEAT))
			.expectSubscription()
			.assertNext(EscalationAlertControllerTest::assertHeartbeat)
			.expectNoEvent(HEARTBEAT.minusSeconds(1))
			.thenAwait(Duration.ofSeconds(1))
			.assertNext(EscalationAlertControllerTest::assertHeartbeat)
			.thenAwait(HEARTBEAT)
			.assertNext(EscalationAlertControllerTest::assertHeartbeat)
			.thenCancel()
			.verify();
	}

	@Test
	void alertIsANamedEventWithTheEventIdAndTheContractFields() {
		UUID eventId = UUID.randomUUID();
		UUID patientId = UUID.randomUUID();
		Instant escalatedAt = Instant.parse("2026-11-13T10:00:00Z");
		EscalationAlert alert = new EscalationAlert(eventId, patientId, TriageLevel.CRITICAL, escalatedAt);

		StepVerifier.withVirtualTime(() -> EscalationAlertController.events(Flux.just(alert), HEARTBEAT))
			.assertNext(event -> {
				assertThat(event.event()).isEqualTo("alerta_escalamiento");
				assertThat(event.id()).isEqualTo(eventId.toString());
				assertThat(event.data()).isEqualTo(new EscalationAlertResponse(patientId, "CRITICO", escalatedAt,
						"Paciente CRITICO superó su SLA sin ser atendido"));
			})
			.assertNext(EscalationAlertControllerTest::assertHeartbeat)
			.thenCancel()
			.verify();
	}

	@Test
	void levelUsesTheContractValue() {
		EscalationAlert alert = new EscalationAlert(null, UUID.randomUUID(), TriageLevel.NON_URGENT_2, Instant.now());

		EscalationAlertResponse response = EscalationAlertResponse.from(alert);

		assertThat(response.nivel()).isEqualTo("NO_URGENTE_2");
		assertThat(response.mensaje()).isEqualTo("Paciente NO_URGENTE_2 superó su SLA sin ser atendido");
	}

	private static void assertHeartbeat(ServerSentEvent<EscalationAlertResponse> event) {
		assertThat(event.comment()).isEqualTo("heartbeat");
		assertThat(event.data()).isNull();
	}

}
