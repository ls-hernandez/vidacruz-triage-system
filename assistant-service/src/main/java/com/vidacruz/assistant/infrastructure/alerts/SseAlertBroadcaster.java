package com.vidacruz.assistant.infrastructure.alerts;

import com.vidacruz.assistant.application.port.AlertNotifier;
import com.vidacruz.assistant.domain.EscalationAlert;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * RF-06: fans each alert out, in memory, to the clients connected to the SSE stream of this instance.
 * <p>
 * The sink is a {@code directBestEffort} multicast: no replay and no shared buffer, so a client only receives the alerts
 * emitted while it is connected. Each client reads through its own bounded buffer that requests unbounded demand from
 * the sink: the sink never waits for anyone, and when one client falls behind its oldest pending alerts are dropped
 * (and logged) for that client only. Other clients and the Kafka listener are not affected.
 */
public class SseAlertBroadcaster implements AlertNotifier {

	private static final Logger log = LoggerFactory.getLogger(SseAlertBroadcaster.class);

	/** Spin time if two listener threads emit at the same instant; an in-memory emit takes nanoseconds. */
	private static final Duration CONCURRENT_EMIT_SPIN = Duration.ofMillis(100);

	private final Sinks.Many<EscalationAlert> sink = Sinks.many().multicast().directBestEffort();
	private final int clientBuffer;

	public SseAlertBroadcaster(int clientBuffer) {
		this.clientBuffer = clientBuffer;
	}

	@Override
	public Mono<Void> notify(EscalationAlert alert) {
		return Mono.fromRunnable(() -> {
			if (sink.currentSubscriberCount() == 0) {
				log.debug("No client connected to the alert stream; escalation of patient {} not delivered",
						alert.patientId());
				return;
			}
			// FAIL_NON_SERIALIZED (concurrent emit) is retried; FAIL_ZERO_SUBSCRIBER (last client left) is not
			sink.emitNext(alert, Sinks.EmitFailureHandler.busyLooping(CONCURRENT_EMIT_SPIN));
			log.info("Escalation alert for patient {} ({}) sent to {} client(s)", alert.patientId(), alert.level(),
					sink.currentSubscriberCount());
		});
	}

	/** Alerts for one client, from now on; {@code clientId} only identifies it in the logs. */
	public Flux<EscalationAlert> alerts(String clientId) {
		return sink.asFlux()
			.onBackpressureBuffer(clientBuffer,
					dropped -> log.warn("Alert stream client {} is too slow; dropped alert of patient {} (event {})",
							clientId, dropped.patientId(), dropped.eventId()),
					BufferOverflowStrategy.DROP_OLDEST)
			.doOnSubscribe(subscription -> log.info("Alert stream client {} connected", clientId))
			.doFinally(signal -> log.info("Alert stream client {} disconnected ({}); {} client(s) left", clientId,
					signal, sink.currentSubscriberCount()));
	}

	/** Clients currently connected to this instance. */
	public int connectedClients() {
		return sink.currentSubscriberCount();
	}

}
