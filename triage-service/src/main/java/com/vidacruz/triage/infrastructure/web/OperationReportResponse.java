package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.HourlyOperationStats;
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RF-05 body of {@code GET /triage/reportes/operacion}. {@code desde} and {@code hasta} are echoed in UTC;
 * {@code hora} carries the offset of {@code zonaHoraria} (e.g. {@code 2026-11-13T00:00:00-05:00}).
 */
record OperationReportResponse(Instant desde, Instant hasta, String zonaHoraria, List<HourResponse> horas) {

	/**
	 * One hour of the report. {@code conteoPorNivel} always has the 5 contract levels, in severity order; the wait
	 * fields are minutes rounded to 2 decimals, {@code null} when nobody registered in that hour entered consultation.
	 */
	record HourResponse(
			String hora,
			long totalRegistrados,
			Map<String, Long> conteoPorNivel,
			long pacientesAtendidos,
			BigDecimal esperaPromedioMinutos,
			BigDecimal esperaP95Minutos) {
	}

	static OperationReportResponse from(OperationReportParameters params, List<HourlyOperationStats> hours) {
		return new OperationReportResponse(params.from(), params.to(), params.zone().getId(),
				hours.stream().map(stats -> hour(stats, params.zone())).toList());
	}

	private static HourResponse hour(HourlyOperationStats stats, ZoneId zone) {
		Map<String, Long> byLevel = new LinkedHashMap<>();
		for (TriageLevel level : TriageLevel.values()) {
			byLevel.put(ContractMapper.level(level), stats.countByLevel().get(level));
		}
		// formatted here so Jackson cannot normalize the offset to UTC
		String hour = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(stats.hourStart().atZone(zone).toOffsetDateTime());
		return new HourResponse(hour, stats.totalRegistered(), byLevel, stats.attended(), stats.averageWaitMinutes(),
				stats.p95WaitMinutes());
	}

}
