package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.infrastructure.alerts.AlertProperties;
import com.vidacruz.assistant.infrastructure.alerts.SseAlertBroadcaster;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RF-06: live escalation alerts for the medical team's board, as Server-Sent Events (ADR-004). Protected by the API
 * key like every other endpoint.
 * <p>
 * Only alerts emitted while the client is connected are sent: there is no history and {@code Last-Event-ID} is not
 * honoured. The {@code id} of each event is the {@code eventId} of the escalation, so a client can ignore a repeated
 * alert (Kafka delivers at least once).
 */
@RestController
class EscalationAlertController {

	static final String EVENT_NAME = "alerta_escalamiento";
	static final String HEARTBEAT = "heartbeat";

	private final SseAlertBroadcaster broadcaster;
	private final Duration heartbeat;

	EscalationAlertController(SseAlertBroadcaster broadcaster, AlertProperties properties) {
		this.broadcaster = broadcaster;
		this.heartbeat = properties.heartbeat();
	}

	@GetMapping(path = "/asistente/alertas/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Flux<ServerSentEvent<EscalationAlertResponse>> stream(ServerHttpRequest request) {
		return events(broadcaster.alerts(request.getId()), heartbeat);
	}

	/**
	 * Alerts merged with a periodic comment. The alerts are subscribed first, so the client is registered before the
	 * first heartbeat (sent immediately) flushes the response headers. The heartbeat drops ticks the client did not
	 * request instead of failing with an overflow.
	 */
	static Flux<ServerSentEvent<EscalationAlertResponse>> events(Flux<EscalationAlert> alerts, Duration heartbeat) {
		Flux<ServerSentEvent<EscalationAlertResponse>> alertEvents = alerts
			.map(alert -> ServerSentEvent.builder(EscalationAlertResponse.from(alert))
				.event(EVENT_NAME)
				.id(alert.eventId() != null ? alert.eventId().toString() : null)
				.build());
		Flux<ServerSentEvent<EscalationAlertResponse>> heartbeats = Flux.interval(Duration.ZERO, heartbeat)
			.onBackpressureDrop()
			.map(tick -> ServerSentEvent.<EscalationAlertResponse>builder().comment(HEARTBEAT).build());
		return Flux.merge(alertEvents, heartbeats);
	}

}
