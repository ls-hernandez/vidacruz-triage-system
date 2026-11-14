package com.vidacruz.assistant.application.port;

import com.vidacruz.assistant.domain.EscalationAlert;
import reactor.core.publisher.Mono;

/**
 * RF-06: delivers an escalation alert to the medical team. Today the only channel is Server-Sent Events; other
 * channels (a Teams webhook, e-mail) are new implementations of this port, combined by a composite notifier, without
 * touching the use case (ADR-004).
 * <p>
 * Contract: must not block, and should complete quickly. Delivery is best effort and at least once: the same alert may
 * be delivered again if Kafka redelivers the event.
 */
public interface AlertNotifier {

	Mono<Void> notify(EscalationAlert alert);

}
