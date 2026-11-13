package com.vidacruz.assistant.infrastructure.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict text fields: {@code "pregunta": 123} or {@code true} is a 400, not {@code "123"}.
 * {@code spring.jackson.mapper.allow-coercion-of-scalars=false} does not cover number/boolean → String, which Jackson
 * controls with its coercion configuration per logical type.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

	@Bean
	Jackson2ObjectMapperBuilderCustomizer strictTextCoercion() {
		return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}

}
