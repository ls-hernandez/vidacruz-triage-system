package com.vidacruz.triage.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Classifies a patient according to RN-01.
 * <p>
 * Levels 1 to 3 are evaluated in order and the first match wins, so the most severe level prevails.
 * Level 5 applies only to a patient that would otherwise be level 4 and whose normalized chief complaint
 * matches an administrative complaint exactly.
 */
public final class TriageClassifier {

	private static final BigDecimal FEVER_THRESHOLD = new BigDecimal("38.0");
	private static final BigDecimal HIGH_FEVER_THRESHOLD = new BigDecimal("38.5");

	private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private static final Set<String> ADMINISTRATIVE_COMPLAINTS = Stream.of(
				"documentos",
				"recolección de resultados",
				"renovación de recetario",
				"vacunación",
				"seguimiento")
		.map(TriageClassifier::normalize)
		.collect(Collectors.toUnmodifiableSet());

	public TriageLevel classify(VitalSigns vitalSigns, String chiefComplaint) {
		Objects.requireNonNull(vitalSigns, "vitalSigns must not be null");
		Objects.requireNonNull(chiefComplaint, "chiefComplaint must not be null");

		if (isCritical(vitalSigns)) {
			return TriageLevel.CRITICAL;
		}
		if (isUrgent(vitalSigns)) {
			return TriageLevel.URGENT;
		}
		if (isUrgent2(vitalSigns)) {
			return TriageLevel.URGENT_2;
		}
		return isAdministrative(chiefComplaint) ? TriageLevel.NON_URGENT_2 : TriageLevel.NON_URGENT;
	}

	private static boolean isCritical(VitalSigns v) {
		return v.heartRate() < 40
				|| v.heartRate() > 120
				|| v.oxygenSaturation() < 90
				|| v.systolicPressure() < 90
				|| v.glasgowScale() <= 8;
	}

	private static boolean isUrgent(VitalSigns v) {
		return between(v.oxygenSaturation(), 90, 93)
				|| between(v.systolicPressure(), 90, 100)
				|| v.temperature().compareTo(HIGH_FEVER_THRESHOLD) >= 0
				|| between(v.glasgowScale(), 9, 13);
	}

	private static boolean isUrgent2(VitalSigns v) {
		return between(v.heartRate(), 90, 120)
				|| (v.temperature().compareTo(FEVER_THRESHOLD) >= 0 && v.temperature().compareTo(HIGH_FEVER_THRESHOLD) < 0)
				|| v.painLevel() >= 7;
	}

	private static boolean between(int value, int minInclusive, int maxInclusive) {
		return value >= minInclusive && value <= maxInclusive;
	}

	private static boolean isAdministrative(String chiefComplaint) {
		return ADMINISTRATIVE_COMPLAINTS.contains(normalize(chiefComplaint));
	}

	/** Lower case, without diacritics, trimmed and with internal whitespace collapsed to one space. */
	private static String normalize(String text) {
		String withoutDiacritics = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
		return WHITESPACE.matcher(withoutDiacritics.toLowerCase(Locale.ROOT).strip()).replaceAll(" ");
	}

}
