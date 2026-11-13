package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.OperationReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** RF-05: operation report grouped by registration hour over the half-open range {@code [from, to)}. */
public class GetOperationReport {

	/** Widest accepted range, to bound the size of the report (at most 744 hours). */
	public static final Duration MAX_RANGE = Duration.ofDays(31);

	private final OperationReportRepository reports;

	public GetOperationReport(OperationReportRepository reports) {
		this.reports = reports;
	}

	/**
	 * The caller validates the parameters (with field names for the API); an invalid range here is a programming
	 * error. The list only holds the rows already aggregated by the database.
	 */
	public Mono<List<HourlyOperationStats>> report(Instant from, Instant to, ZoneId zone) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(zone, "zone");
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
			return Mono.error(new IllegalArgumentException("Invalid report range: " + from + " - " + to));
		}
		return reports.hourlyStats(from, to, zone).collectList();
	}

}
