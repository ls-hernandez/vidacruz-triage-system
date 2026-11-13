package com.vidacruz.triage.infrastructure.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field rule of RF-01: the diastolic pressure must be strictly lower than the systolic pressure.
 * The violation is reported on {@code presionDiastolica} so the 400 names the field.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DiastolicBelowSystolic.Validator.class)
@interface DiastolicBelowSystolic {

	String message() default "debe ser menor que presionSistolica";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<DiastolicBelowSystolic, RegisterPatientRequest> {

		@Override
		public boolean isValid(RegisterPatientRequest request, ConstraintValidatorContext context) {
			if (request == null || request.presionSistolica() == null || request.presionDiastolica() == null) {
				return true; // missing values are reported by @NotNull
			}
			if (request.presionDiastolica() < request.presionSistolica()) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode("presionDiastolica")
				.addConstraintViolation();
			return false;
		}

	}

}
