package com.vidacruz.triage.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Vital signs taken at triage. Range validation belongs to the registration use case (RF-01).
 *
 * @param heartRate         beats per minute
 * @param systolicPressure  mmHg
 * @param diastolicPressure mmHg
 * @param oxygenSaturation  SpO2 percentage
 * @param temperature       degrees Celsius
 * @param glasgowScale      Glasgow Coma Scale (3-15)
 * @param painLevel         pain scale (0-10)
 */
public record VitalSigns(
		int heartRate,
		int systolicPressure,
		int diastolicPressure,
		int oxygenSaturation,
		BigDecimal temperature,
		int glasgowScale,
		int painLevel) {

	public VitalSigns {
		Objects.requireNonNull(temperature, "temperature must not be null");
	}

}
