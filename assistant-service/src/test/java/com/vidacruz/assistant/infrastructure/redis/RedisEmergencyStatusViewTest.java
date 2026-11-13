package com.vidacruz.assistant.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.TestcontainersConfiguration;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.TriageLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Lua scripts against a real Redis: the view must end in the same state whatever the order (or concurrency) in
 * which the events of a patient are applied (assumption 25).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisEmergencyStatusViewTest {

	@Autowired
	private EmergencyStatusView view;

	@Autowired
	private ReactiveStringRedisTemplate redis;

	@Test
	void anyOrderOfRegisterEscalateAndDischargeEndsDischarged() {
		List<Function<UUID, Mono<Void>>> events = List.of(
				id -> view.markActive(id, TriageLevel.URGENT),
				id -> view.markActive(id, TriageLevel.URGENT), // escalation: same level
				view::markDischarged);

		for (List<Integer> order : permutations(List.of(0, 1, 2))) {
			UUID sequential = UUID.randomUUID();
			Flux.fromIterable(order).concatMap(i -> events.get(i).apply(sequential)).then().block();
			assertDischarged(sequential, "sequential " + order);

			UUID concurrent = UUID.randomUUID();
			Flux.fromIterable(order).flatMap(i -> events.get(i).apply(concurrent)).then().block();
			assertDischarged(concurrent, "concurrent " + order);
		}
	}

	@Test
	void activePatientIsInExactlyOneLevelSet() {
		UUID id = UUID.randomUUID();
		view.markActive(id, TriageLevel.NON_URGENT_2).then(view.markActive(id, TriageLevel.NON_URGENT_2)).block();

		for (TriageLevel level : TriageLevel.values()) {
			assertThat(isMember(RedisEmergencyStatusView.activeKey(level), id))
				.as(level.name())
				.isEqualTo(level == TriageLevel.NON_URGENT_2);
		}
		assertThat(isMember(RedisEmergencyStatusView.DISCHARGED_KEY, id)).isFalse();
	}

	@Test
	void countsComeFromTheLevelSets() {
		long before = view.countActiveByLevel().block().get(TriageLevel.CRITICAL);

		view.markActive(UUID.randomUUID(), TriageLevel.CRITICAL)
			.then(view.markActive(UUID.randomUUID(), TriageLevel.CRITICAL))
			.block();

		assertThat(view.countActiveByLevel().block()).containsKeys(TriageLevel.values());
		assertThat(view.countActiveByLevel().block().get(TriageLevel.CRITICAL)).isEqualTo(before + 2);
	}

	private void assertDischarged(UUID id, String description) {
		for (TriageLevel level : TriageLevel.values()) {
			assertThat(isMember(RedisEmergencyStatusView.activeKey(level), id)).as(description + " " + level)
				.isFalse();
		}
		assertThat(isMember(RedisEmergencyStatusView.DISCHARGED_KEY, id)).as(description).isTrue();
	}

	private boolean isMember(String key, UUID id) {
		return Boolean.TRUE.equals(redis.opsForSet().isMember(key, id.toString()).block());
	}

	private static List<List<Integer>> permutations(List<Integer> items) {
		if (items.isEmpty()) {
			return List.of(List.of());
		}
		List<List<Integer>> result = new ArrayList<>();
		for (Integer head : items) {
			List<Integer> rest = new ArrayList<>(items);
			rest.remove(head);
			for (List<Integer> tail : permutations(rest)) {
				List<Integer> permutation = new ArrayList<>();
				permutation.add(head);
				permutation.addAll(tail);
				result.add(permutation);
			}
		}
		return result;
	}

}
