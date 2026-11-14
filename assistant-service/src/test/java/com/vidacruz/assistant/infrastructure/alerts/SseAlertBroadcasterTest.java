package com.vidacruz.assistant.infrastructure.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

/** Fan-out and per-client backpressure of the SSE channel, without HTTP. */
class SseAlertBroadcasterTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void everyConnectedClientReceivesTheAlert() {
		SseAlertBroadcaster broadcaster = new SseAlertBroadcaster(8);
		EscalationAlert alert = alert();

		StepVerifier first = StepVerifier.create(broadcaster.alerts("a")).expectNext(alert).thenCancel().verifyLater();
		StepVerifier second = StepVerifier.create(broadcaster.alerts("b")).expectNext(alert).thenCancel().verifyLater();
		assertThat(broadcaster.connectedClients()).isEqualTo(2);

		StepVerifier.create(broadcaster.notify(alert)).verifyComplete();

		first.verify(TIMEOUT);
		second.verify(TIMEOUT);
		assertThat(broadcaster.connectedClients()).isZero();
	}

	@Test
	void slowClientLosesItsOldestAlertsWithoutAffectingTheOthers() {
		SseAlertBroadcaster broadcaster = new SseAlertBroadcaster(2);
		EscalationAlert a1 = alert();
		EscalationAlert a2 = alert();
		EscalationAlert a3 = alert();
		EscalationAlert a4 = alert();

		// requests nothing until all four alerts were emitted: only the last two fit in its buffer
		List<EscalationAlert> slowReceived = new CopyOnWriteArrayList<>();
		BaseSubscriber<EscalationAlert> slow = new BaseSubscriber<>() {
			@Override
			protected void hookOnSubscribe(Subscription subscription) {
				// no demand yet
			}

			@Override
			protected void hookOnNext(EscalationAlert alert) {
				slowReceived.add(alert);
			}
		};
		broadcaster.alerts("slow").subscribe(slow);
		StepVerifier fast = StepVerifier.create(broadcaster.alerts("fast"))
			.expectNext(a1, a2, a3, a4)
			.thenCancel()
			.verifyLater();

		for (EscalationAlert alert : List.of(a1, a2, a3, a4)) {
			StepVerifier.create(broadcaster.notify(alert)).verifyComplete();
		}

		fast.verify(TIMEOUT);
		slow.request(4);
		assertThat(slowReceived).containsExactly(a3, a4);
		slow.dispose();
	}

	@Test
	void alertWithoutClientsCompletesAndIsNotReplayedLater() {
		SseAlertBroadcaster broadcaster = new SseAlertBroadcaster(8);
		EscalationAlert missed = alert();
		EscalationAlert seen = alert();

		StepVerifier.create(broadcaster.notify(missed)).verifyComplete();

		StepVerifier late = StepVerifier.create(broadcaster.alerts("late")).expectNext(seen).thenCancel().verifyLater();
		StepVerifier.create(broadcaster.notify(seen)).verifyComplete();
		late.verify(TIMEOUT);
	}

	private static EscalationAlert alert() {
		return new EscalationAlert(UUID.randomUUID(), UUID.randomUUID(), TriageLevel.CRITICAL, Instant.now());
	}

}
