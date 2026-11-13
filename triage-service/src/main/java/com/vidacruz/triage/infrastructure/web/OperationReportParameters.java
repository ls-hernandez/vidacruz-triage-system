package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.GetOperationReport;
import com.vidacruz.triage.infrastructure.web.ApiExceptionHandler.InvalidField;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * RF-05 query parameters, validated all at once so the 400 names every invalid one (assumptions 20 and 21).
 *
 * @param from inclusive start ({@code desde})
 * @param to   exclusive end ({@code hasta})
 * @param zone time zone used to group by hour ({@code zonaHoraria}, UTC by default)
 */
record OperationReportParameters(Instant from, Instant to, ZoneId zone) {

	static final String FROM = "desde";
	static final String TO = "hasta";
	static final String ZONE = "zonaHoraria";

	/** The region id {@code UTC} (not the offset {@code Z}), which both Java and PostgreSQL understand. */
	static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

	private static final String REQUIRED = "es obligatorio";
	private static final String INVALID_DATE =
			"debe ser una fecha ISO 8601 con zona u offset, por ejemplo 2026-11-13T00:00:00Z";
	private static final String NOT_AFTER_FROM = "debe ser posterior a desde";
	private static final String RANGE_TOO_WIDE = "el rango entre desde y hasta no puede superar "
			+ GetOperationReport.MAX_RANGE.toDays() + " días";
	private static final String INVALID_ZONE = "debe ser una zona horaria IANA válida, por ejemplo America/Bogota";

	/** @throws InvalidRequestParametersException listing every invalid parameter */
	static OperationReportParameters parse(String desde, String hasta, String zonaHoraria) {
		List<InvalidField> errors = new ArrayList<>();
		Instant from = parseInstant(FROM, desde, errors);
		Instant to = parseInstant(TO, hasta, errors);
		ZoneId zone = parseZone(zonaHoraria, errors);
		if (from != null && to != null) {
			if (!from.isBefore(to)) {
				errors.add(new InvalidField(TO, NOT_AFTER_FROM));
			}
			else if (Duration.between(from, to).compareTo(GetOperationReport.MAX_RANGE) > 0) {
				errors.add(new InvalidField(TO, RANGE_TOO_WIDE));
			}
		}
		if (!errors.isEmpty()) {
			throw new InvalidRequestParametersException(errors);
		}
		return new OperationReportParameters(from, to, zone);
	}

	/** ISO 8601 with {@code Z} or an explicit offset; a local date-time is ambiguous and rejected. */
	private static Instant parseInstant(String field, String value, List<InvalidField> errors) {
		if (value == null || value.isBlank()) {
			errors.add(new InvalidField(field, REQUIRED));
			return null;
		}
		try {
			return OffsetDateTime.parse(value.strip(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ex) {
			errors.add(new InvalidField(field, INVALID_DATE));
			return null;
		}
	}

	/**
	 * Only IANA region ids (plus {@code UTC}) are accepted. Offsets and ids such as {@code GMT+5} are rejected:
	 * PostgreSQL reads those with the POSIX convention (inverted sign), so Java and the database would disagree.
	 */
	private static ZoneId parseZone(String value, List<InvalidField> errors) {
		if (value == null || value.isBlank()) {
			return DEFAULT_ZONE;
		}
		String id = value.strip();
		if (!ZoneId.getAvailableZoneIds().contains(id)) {
			errors.add(new InvalidField(ZONE, INVALID_ZONE));
			return null;
		}
		return ZoneId.of(id);
	}

}
