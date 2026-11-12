package com.vidacruz.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TriageClassifierTest {

	private static final String NON_ADMINISTRATIVE_COMPLAINT = "Dolor abdominal";

	private final TriageClassifier classifier = new TriageClassifier();

	/** Normal vital signs that match no rule: FC 75, 120/80, SpO2 98, 36.5 °C, GCS 15, pain 0. */
	private static VitalSigns normalVitals() {
		return new VitalSigns(75, 120, 80, 98, new BigDecimal("36.5"), 15, 0);
	}

	static Stream<Arguments> challengeExamples() {
		return Stream.of(
				Arguments.of("E1", new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8),
						"Dolor torácico", TriageLevel.CRITICAL),
				Arguments.of("E2", new VitalSigns(95, 150, 95, 91, new BigDecimal("37.0"), 15, 4),
						"Dificultad para respirar", TriageLevel.URGENT),
				Arguments.of("E3", new VitalSigns(88, 130, 85, 98, new BigDecimal("38.2"), 15, 6),
						"Fiebre", TriageLevel.URGENT_2),
				Arguments.of("E4", new VitalSigns(72, 120, 80, 99, new BigDecimal("36.5"), 15, 2),
						"Herida por cortar", TriageLevel.NON_URGENT),
				Arguments.of("E5", new VitalSigns(70, 115, 75, 99, new BigDecimal("36.4"), 15, 0),
						"Renovación de recetario", TriageLevel.NON_URGENT_2),
				Arguments.of("E6", new VitalSigns(35, 80, 50, 85, new BigDecimal("35.9"), 6, 0),
						"Inconsciente", TriageLevel.CRITICAL));
	}

	@ParameterizedTest(name = "{0} -> {3}")
	@MethodSource("challengeExamples")
	void classifiesChallengeExamples(String example, VitalSigns vitals, String complaint, TriageLevel expected) {
		assertThat(classifier.classify(vitals, complaint)).isEqualTo(expected);
	}

	@Test
	void normalVitalsAreNonUrgent() {
		assertThat(classifier.classify(normalVitals(), NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(TriageLevel.NON_URGENT);
	}

	@ParameterizedTest(name = "heart rate {0} -> {1}")
	@CsvSource({
			"39, CRITICAL",
			"40, NON_URGENT",
			"89, NON_URGENT",
			"90, URGENT_2",
			"120, URGENT_2",
			"121, CRITICAL"})
	void heartRateBoundaries(int heartRate, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(heartRate, n.systolicPressure(), n.diastolicPressure(),
				n.oxygenSaturation(), n.temperature(), n.glasgowScale(), n.painLevel());

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "SpO2 {0} -> {1}")
	@CsvSource({
			"89, CRITICAL",
			"90, URGENT",
			"93, URGENT",
			"94, NON_URGENT"})
	void oxygenSaturationBoundaries(int spo2, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(n.heartRate(), n.systolicPressure(), n.diastolicPressure(),
				spo2, n.temperature(), n.glasgowScale(), n.painLevel());

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "systolic {0} -> {1}")
	@CsvSource({
			"89, CRITICAL",
			"90, URGENT",
			"100, URGENT",
			"101, NON_URGENT"})
	void systolicPressureBoundaries(int systolic, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(n.heartRate(), systolic, n.diastolicPressure(),
				n.oxygenSaturation(), n.temperature(), n.glasgowScale(), n.painLevel());

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "temperature {0} -> {1}")
	@CsvSource({
			"37.9, NON_URGENT",
			"38.0, URGENT_2",
			"38.4, URGENT_2",
			"38.5, URGENT",
			// Same value with a different scale: equals() would say 38.50 != 38.5, compareTo() must not.
			"38.50, URGENT"})
	void temperatureBoundaries(String temperature, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(n.heartRate(), n.systolicPressure(), n.diastolicPressure(),
				n.oxygenSaturation(), new BigDecimal(temperature), n.glasgowScale(), n.painLevel());

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "GCS {0} -> {1}")
	@CsvSource({
			"8, CRITICAL",
			"9, URGENT",
			"13, URGENT",
			"14, NON_URGENT"})
	void glasgowScaleBoundaries(int gcs, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(n.heartRate(), n.systolicPressure(), n.diastolicPressure(),
				n.oxygenSaturation(), n.temperature(), gcs, n.painLevel());

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "pain {0} -> {1}")
	@CsvSource({
			"6, NON_URGENT",
			"7, URGENT_2"})
	void painLevelBoundaries(int pain, TriageLevel expected) {
		VitalSigns n = normalVitals();
		VitalSigns vitals = new VitalSigns(n.heartRate(), n.systolicPressure(), n.diastolicPressure(),
				n.oxygenSaturation(), n.temperature(), n.glasgowScale(), pain);

		assertThat(classifier.classify(vitals, NON_ADMINISTRATIVE_COMPLAINT)).isEqualTo(expected);
	}

	@Test
	void administrativeComplaintWithFeverIsNotLevel5() {
		VitalSigns n = normalVitals();
		VitalSigns feverish = new VitalSigns(n.heartRate(), n.systolicPressure(), n.diastolicPressure(),
				n.oxygenSaturation(), new BigDecimal("38.2"), n.glasgowScale(), n.painLevel());

		assertThat(classifier.classify(feverish, "Renovación de recetario")).isEqualTo(TriageLevel.URGENT_2);
	}

	@ParameterizedTest(name = "\"{0}\" -> NON_URGENT_2")
	@ValueSource(strings = {
			"RENOVACIÓN DE RECETARIO",
			"renovacion de recetario",
			"  Renovación   de  recetario  ",
			"Recolección de resultados",
			"VACUNACION",
			"documentos",
			"Seguimiento"})
	void administrativeComplaintVariantsAreLevel5(String complaint) {
		assertThat(classifier.classify(normalVitals(), complaint)).isEqualTo(TriageLevel.NON_URGENT_2);
	}

	@ParameterizedTest(name = "\"{0}\" -> NON_URGENT")
	@ValueSource(strings = {
			"renovación de recetario médico",
			"documento",
			"seguimiento de herida"})
	void similarButNotExactComplaintsAreLevel4(String complaint) {
		assertThat(classifier.classify(normalVitals(), complaint)).isEqualTo(TriageLevel.NON_URGENT);
	}

	@Test
	void levelNumbersGoFrom1To5InSeverityOrder() {
		assertThat(Arrays.stream(TriageLevel.values()).mapToInt(TriageLevel::number)).containsExactly(1, 2, 3, 4, 5);
	}

}
