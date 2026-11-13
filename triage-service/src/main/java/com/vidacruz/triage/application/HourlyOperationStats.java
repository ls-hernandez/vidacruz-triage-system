package com.vidacruz.triage.application;

import com.vidacruz.triage.domain.TriageLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * RF-05: aggregates of the patients registered during one hour, all computed by the database.
 *
 * @param hourStart          start of the hour (bucket in the requested time zone, as an instant)
 * @param totalRegistered    patients registered in that hour
 * @param countByLevel       patients per level; always contains the 5 levels
 * @param attended           patients of that hour that entered consultation (at any time)
 * @param averageWaitMinutes average minutes from registration to first consultation; {@code null} if nobody entered
 * @param p95WaitMinutes     95th percentile (linear interpolation) of that wait; {@code null} if nobody entered
 */
public record HourlyOperationStats(
		Instant hourStart,
		long totalRegistered,
		Map<TriageLevel, Long> countByLevel,
		long attended,
		BigDecimal averageWaitMinutes,
		BigDecimal p95WaitMinutes) {

	public HourlyOperationStats {
		EnumMap<TriageLevel, Long> counts = new EnumMap<>(TriageLevel.class);
		for (TriageLevel level : TriageLevel.values()) {
			counts.put(level, countByLevel.getOrDefault(level, 0L));
		}
		countByLevel = Collections.unmodifiableMap(counts);
	}

}
