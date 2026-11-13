package com.vidacruz.assistant.application;

import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.TriageLevel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/** RF-04 B: active patients per level (every level present, zero when empty). */
public class GetEmergencyStatus {

	private final EmergencyStatusView view;

	public GetEmergencyStatus(EmergencyStatusView view) {
		this.view = view;
	}

	public Mono<Map<TriageLevel, Long>> activeByLevel() {
		return view.countActiveByLevel().map(counts -> {
			Map<TriageLevel, Long> all = new EnumMap<>(TriageLevel.class);
			for (TriageLevel level : TriageLevel.values()) {
				all.put(level, counts.getOrDefault(level, 0L));
			}
			return Collections.unmodifiableMap(all);
		});
	}

}
