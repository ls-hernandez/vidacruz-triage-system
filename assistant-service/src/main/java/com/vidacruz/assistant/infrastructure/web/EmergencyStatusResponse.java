package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.domain.TriageLevel;
import com.vidacruz.assistant.infrastructure.contract.ContractMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** RF-04 B body: active patients (everything but {@code ALTA}) in total and per level, the 5 levels always present. */
record EmergencyStatusResponse(long totalActivos, Map<String, Long> porNivel) {

	static EmergencyStatusResponse from(Map<TriageLevel, Long> activeByLevel) {
		Map<String, Long> byLevel = new LinkedHashMap<>();
		long total = 0;
		for (TriageLevel level : TriageLevel.values()) {
			long count = activeByLevel.getOrDefault(level, 0L);
			byLevel.put(ContractMapper.level(level), count);
			total += count;
		}
		return new EmergencyStatusResponse(total, byLevel);
	}

}
