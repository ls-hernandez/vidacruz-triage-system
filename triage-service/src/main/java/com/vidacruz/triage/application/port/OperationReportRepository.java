package com.vidacruz.triage.application.port;

import com.vidacruz.triage.application.HourlyOperationStats;
import java.time.Instant;
import java.time.ZoneId;
import reactor.core.publisher.Flux;

/** RF-05: hourly operation aggregates, computed entirely by the database. */
public interface OperationReportRepository {

	/**
	 * One element per hour of {@code zone} from the hour containing {@code from} up to {@code to} (exclusive), in
	 * order, including hours without patients. Only patients with {@code from <= registeredAt < to} are counted; the
	 * wait to the first consultation is measured even if that consultation happened after {@code to}.
	 */
	Flux<HourlyOperationStats> hourlyStats(Instant from, Instant to, ZoneId zone);

}
