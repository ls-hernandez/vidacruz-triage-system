package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.HourlyOperationStats;
import com.vidacruz.triage.application.port.OperationReportRepository;
import com.vidacruz.triage.domain.TriageLevel;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * RF-05 in a single SQL statement: every aggregate (counts, average, percentile) is computed by PostgreSQL; Java only
 * maps the resulting rows (at most one per hour of the range).
 * <p>
 * Buckets use {@code date_trunc('hour', ts, zone)}, which truncates in the given zone but returns a
 * {@code timestamptz}: each real hour is its own bucket even across daylight saving changes (assumption 21).
 * <p>
 * Index usage (checked with EXPLAIN ANALYZE on PostgreSQL 16, 400k patients, one-day range):
 * {@code patients_in_range} is an index range scan of {@code idx_patients_registered_at}; {@code consultations} is
 * joined only with those patients, as a nested loop over {@code idx_audit_entries_patient_occurred_at}
 * ({@code patient_id = ?}), so the window function only sorts the audit rows of patients in the range.
 */
@Repository
class R2dbcOperationReportRepository implements OperationReportRepository {

	private static final String HOURLY_STATS_SQL = """
			WITH patients_in_range AS (
			    SELECT id, level, registered_at,
			           date_trunc('hour', registered_at, CAST(:zone AS text)) AS hour_start
			    FROM patients
			    WHERE registered_at >= CAST(:from AS timestamptz) AND registered_at < CAST(:to AS timestamptz)
			),
			consultations AS (
			    -- first entry to consultation per patient (assumption 4); not limited by :to on purpose
			    SELECT a.patient_id, a.occurred_at,
			           ROW_NUMBER() OVER (PARTITION BY a.patient_id ORDER BY a.occurred_at, a.id) AS rn
			    FROM audit_entries a
			    JOIN patients_in_range p ON p.id = a.patient_id
			    WHERE a.event_type = 'PATIENT_IN_CONSULTATION'
			),
			waits AS (
			    -- wait_minutes is NULL for patients that never entered consultation
			    SELECT p.hour_start, p.level,
			           CAST(EXTRACT(EPOCH FROM (c.occurred_at - p.registered_at)) / 60 AS double precision) AS wait_minutes
			    FROM patients_in_range p
			    LEFT JOIN consultations c ON c.patient_id = p.id AND c.rn = 1
			),
			stats AS (
			    -- AVG, COUNT(expr) and percentile_cont ignore NULLs: patients without consultation only count in totals
			    SELECT hour_start,
			           COUNT(*)                                       AS total,
			           COUNT(*) FILTER (WHERE level = 'CRITICAL')     AS critical,
			           COUNT(*) FILTER (WHERE level = 'URGENT')       AS urgent,
			           COUNT(*) FILTER (WHERE level = 'URGENT_2')     AS urgent_2,
			           COUNT(*) FILTER (WHERE level = 'NON_URGENT')   AS non_urgent,
			           COUNT(*) FILTER (WHERE level = 'NON_URGENT_2') AS non_urgent_2,
			           COUNT(wait_minutes)                            AS attended,
			           AVG(wait_minutes)                              AS avg_wait,
			           percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_minutes) AS p95_wait
			    FROM waits
			    GROUP BY hour_start
			),
			hours AS (
			    -- continuous series, so hours without patients are reported with zeros
			    SELECT h AS hour_start
			    FROM generate_series(date_trunc('hour', CAST(:from AS timestamptz), CAST(:zone AS text)),
			                         CAST(:to AS timestamptz), interval '1 hour') AS h
			    WHERE h < CAST(:to AS timestamptz)
			)
			SELECT h.hour_start,
			       COALESCE(s.total, 0)        AS total,
			       COALESCE(s.critical, 0)     AS critical,
			       COALESCE(s.urgent, 0)       AS urgent,
			       COALESCE(s.urgent_2, 0)     AS urgent_2,
			       COALESCE(s.non_urgent, 0)   AS non_urgent,
			       COALESCE(s.non_urgent_2, 0) AS non_urgent_2,
			       COALESCE(s.attended, 0)     AS attended,
			       round(CAST(s.avg_wait AS numeric), 2) AS avg_wait,
			       round(CAST(s.p95_wait AS numeric), 2) AS p95_wait
			FROM hours h
			LEFT JOIN stats s USING (hour_start)
			ORDER BY h.hour_start
			""";

	private final DatabaseClient db;

	R2dbcOperationReportRepository(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Flux<HourlyOperationStats> hourlyStats(Instant from, Instant to, ZoneId zone) {
		return db.sql(HOURLY_STATS_SQL)
			.bind("from", from)
			.bind("to", to)
			.bind("zone", zone.getId())
			.map(R2dbcOperationReportRepository::toStats)
			.all();
	}

	private static HourlyOperationStats toStats(Readable row) {
		Map<TriageLevel, Long> counts = new EnumMap<>(TriageLevel.class);
		counts.put(TriageLevel.CRITICAL, row.get("critical", Long.class));
		counts.put(TriageLevel.URGENT, row.get("urgent", Long.class));
		counts.put(TriageLevel.URGENT_2, row.get("urgent_2", Long.class));
		counts.put(TriageLevel.NON_URGENT, row.get("non_urgent", Long.class));
		counts.put(TriageLevel.NON_URGENT_2, row.get("non_urgent_2", Long.class));
		return new HourlyOperationStats(
				row.get("hour_start", Instant.class),
				row.get("total", Long.class),
				counts,
				row.get("attended", Long.class),
				row.get("avg_wait", BigDecimal.class),
				row.get("p95_wait", BigDecimal.class));
	}

}
