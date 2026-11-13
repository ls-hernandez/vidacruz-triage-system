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
import java.math.BigDecimal;

/**
 * The value has at most one significant decimal: {@code 38.5} and {@code 38.50} are valid, {@code 38.45} is not.
 * Unlike {@code @Digits}, trailing zeros are ignored. Prevents {@code numeric(3,1)} from silently rounding a
 * temperature, which could change the triage level (assumption 6).
 */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AtMostOneDecimal.Validator.class)
@interface AtMostOneDecimal {

	String message() default "debe tener como máximo un decimal";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<AtMostOneDecimal, BigDecimal> {

		@Override
		public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
			return value == null || value.stripTrailingZeros().scale() <= 1;
		}

	}

}
