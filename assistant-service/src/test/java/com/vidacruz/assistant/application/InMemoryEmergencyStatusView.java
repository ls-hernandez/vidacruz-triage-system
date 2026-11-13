package com.vidacruz.assistant.application;

import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.TriageLevel;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Fake of the view that honours the port contract: sets per level plus a tombstone of discharged patients. */
class InMemoryEmergencyStatusView implements EmergencyStatusView {

	private final Map<TriageLevel, Set<UUID>> active = new EnumMap<>(TriageLevel.class);
	private final Set<UUID> discharged = new HashSet<>();

	InMemoryEmergencyStatusView() {
		for (TriageLevel level : TriageLevel.values()) {
			active.put(level, new HashSet<>());
		}
	}

	@Override
	public Mono<Void> markActive(UUID patientId, TriageLevel level) {
		return Mono.fromRunnable(() -> {
			synchronized (this) {
				if (!discharged.contains(patientId)) {
					active.get(level).add(patientId);
				}
			}
		});
	}

	@Override
	public Mono<Void> markDischarged(UUID patientId) {
		return Mono.fromRunnable(() -> {
			synchronized (this) {
				discharged.add(patientId);
				active.values().forEach(ids -> ids.remove(patientId));
			}
		});
	}

	/** Only the levels with patients, to check that the use case fills in the zeros. */
	@Override
	public Mono<Map<TriageLevel, Long>> countActiveByLevel() {
		return Mono.fromSupplier(() -> {
			synchronized (this) {
				Map<TriageLevel, Long> counts = new EnumMap<>(TriageLevel.class);
				active.forEach((level, ids) -> {
					if (!ids.isEmpty()) {
						counts.put(level, (long) ids.size());
					}
				});
				return counts;
			}
		});
	}

	synchronized boolean isDischarged(UUID patientId) {
		return discharged.contains(patientId);
	}

}
