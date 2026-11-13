package com.vidacruz.assistant.infrastructure.redis;

import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.TriageLevel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Emergency-room view in Redis (assumption 24): one set of patient ids per level ({@code assistant:active:<LEVEL>})
 * and a tombstone set of discharged patients ({@code assistant:discharged}).
 * <p>
 * Writes are Lua scripts so that "check the tombstone, then SADD" and "tombstone, then SREM" are atomic. With sets
 * (not counters) duplicates are no-ops, and with the tombstone the result does not depend on the order in which the
 * events are applied.
 */
@Repository
class RedisEmergencyStatusView implements EmergencyStatusView {

	static final String DISCHARGED_KEY = "assistant:discharged";
	static final String ACTIVE_KEY_PREFIX = "assistant:active:";

	private static final RedisScript<Long> MARK_ACTIVE = RedisScript.of("""
			if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
			    return 0
			end
			return redis.call('SADD', KEYS[2], ARGV[1])
			""", Long.class);

	private static final RedisScript<Long> MARK_DISCHARGED = RedisScript.of("""
			redis.call('SADD', KEYS[1], ARGV[1])
			local removed = 0
			for i = 2, #KEYS do
			    removed = removed + redis.call('SREM', KEYS[i], ARGV[1])
			end
			return removed
			""", Long.class);

	private static final List<String> DISCHARGE_KEYS = dischargeKeys();

	private final ReactiveStringRedisTemplate redis;

	RedisEmergencyStatusView(ReactiveStringRedisTemplate redis) {
		this.redis = redis;
	}

	static String activeKey(TriageLevel level) {
		return ACTIVE_KEY_PREFIX + level.name();
	}

	@Override
	public Mono<Void> markActive(UUID patientId, TriageLevel level) {
		return redis.execute(MARK_ACTIVE, List.of(DISCHARGED_KEY, activeKey(level)), List.of(patientId.toString()))
			.then();
	}

	@Override
	public Mono<Void> markDischarged(UUID patientId) {
		return redis.execute(MARK_DISCHARGED, DISCHARGE_KEYS, List.of(patientId.toString())).then();
	}

	/** One SCARD per level; the five reads are not one atomic snapshot (eventually consistent by milliseconds). */
	@Override
	public Mono<Map<TriageLevel, Long>> countActiveByLevel() {
		return Flux.fromArray(TriageLevel.values())
			.flatMap(level -> redis.opsForSet().size(activeKey(level)).map(count -> Map.entry(level, count)))
			.collectMap(Map.Entry::getKey, Map.Entry::getValue, () -> new EnumMap<>(TriageLevel.class));
	}

	private static List<String> dischargeKeys() {
		List<String> keys = new ArrayList<>();
		keys.add(DISCHARGED_KEY);
		for (TriageLevel level : TriageLevel.values()) {
			keys.add(activeKey(level));
		}
		return List.copyOf(keys);
	}

}
