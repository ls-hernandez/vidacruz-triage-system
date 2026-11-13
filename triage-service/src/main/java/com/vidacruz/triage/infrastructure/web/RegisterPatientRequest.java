package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.RegisterPatientCommand;
import com.vidacruz.triage.domain.VitalSigns;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * RF-01 request body. Field names are the public contract in Spanish; ranges come from "Validaciones de datos".
 * Wrapper types make a missing or {@code null} field a 400 instead of a silent 0. Text fields are stripped before
 * validation, so the length rules apply to the stored value.
 */
@DiastolicBelowSystolic
public record RegisterPatientRequest(
		@NotBlank(message = "es obligatorio")
		@Size(min = 3, max = 100, message = "debe tener entre 3 y 100 caracteres")
		String nombreCompleto,

		@NotNull(message = "es obligatorio")
		@Min(value = 0, message = "debe estar entre 0 y 120 años")
		@Max(value = 120, message = "debe estar entre 0 y 120 años")
		Integer edad,

		@NotNull(message = "es obligatorio")
		@Min(value = 20, message = "debe estar entre 20 y 250 bpm")
		@Max(value = 250, message = "debe estar entre 20 y 250 bpm")
		Integer frecuenciaCardiaca,

		@NotNull(message = "es obligatorio")
		@Min(value = 60, message = "debe estar entre 60 y 260 mmHg")
		@Max(value = 260, message = "debe estar entre 60 y 260 mmHg")
		Integer presionSistolica,

		@NotNull(message = "es obligatorio")
		@Min(value = 30, message = "debe estar entre 30 y 160 mmHg")
		@Max(value = 160, message = "debe estar entre 30 y 160 mmHg")
		Integer presionDiastolica,

		@NotNull(message = "es obligatorio")
		@Min(value = 70, message = "debe estar entre 70 y 100 %")
		@Max(value = 100, message = "debe estar entre 70 y 100 %")
		Integer spo2,

		@NotNull(message = "es obligatorio")
		@DecimalMin(value = "34.0", message = "debe estar entre 34.0 y 42.0 °C")
		@DecimalMax(value = "42.0", message = "debe estar entre 34.0 y 42.0 °C")
		@AtMostOneDecimal
		BigDecimal temperatura,

		@NotNull(message = "es obligatorio")
		@Min(value = 0, message = "debe estar entre 0 y 10")
		@Max(value = 10, message = "debe estar entre 0 y 10")
		Integer dolor,

		@NotNull(message = "es obligatorio")
		@Min(value = 3, message = "debe estar entre 3 y 15")
		@Max(value = 15, message = "debe estar entre 3 y 15")
		Integer gcs,

		@NotBlank(message = "es obligatoria")
		@Size(max = 500, message = "debe tener como máximo 500 caracteres")
		String quejaPrincipal) {

	public RegisterPatientRequest {
		nombreCompleto = nombreCompleto == null ? null : nombreCompleto.strip();
		quejaPrincipal = quejaPrincipal == null ? null : quejaPrincipal.strip();
	}

	RegisterPatientCommand toCommand() {
		VitalSigns vitalSigns = new VitalSigns(frecuenciaCardiaca, presionSistolica, presionDiastolica, spo2,
				temperatura, gcs, dolor);
		return new RegisterPatientCommand(nombreCompleto, edad, vitalSigns, quejaPrincipal);
	}

}
