package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.application.AssistantUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs a blocking call to the AI provider off the event loop ({@code boundedElastic}), with a Resilience4j time limit
 * inside a circuit breaker (so timeouts count as failures), and turns every failure into
 * {@link AssistantUnavailableException} (503). Instances: {@value #EMBEDDING} and {@value #CHAT}
 * ({@code resilience4j.*.instances} in {@code application.yml}).
 */
@Component
class ProviderGuard {

	static final String EMBEDDING = "embedding";
	static final String CHAT = "chat";

	private static final Logger log = LoggerFactory.getLogger(ProviderGuard.class);

	private final CircuitBreakerRegistry circuitBreakers;
	private final TimeLimiterRegistry timeLimiters;

	ProviderGuard(CircuitBreakerRegistry circuitBreakers, TimeLimiterRegistry timeLimiters) {
		this.circuitBreakers = circuitBreakers;
		this.timeLimiters = timeLimiters;
	}

	<T> Mono<T> call(String instance, Callable<T> blockingCall) {
		CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(instance);
		TimeLimiter timeLimiter = timeLimiters.timeLimiter(instance);
		return Mono.fromCallable(blockingCall)
			.subscribeOn(Schedulers.boundedElastic())
			.transformDeferred(TimeLimiterOperator.of(timeLimiter))
			.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
			.onErrorMap(ex -> !(ex instanceof AssistantUnavailableException), ex -> unavailable(instance, ex));
	}

	private static AssistantUnavailableException unavailable(String instance, Throwable ex) {
		if (ex instanceof CallNotPermittedException) {
			log.warn("AI provider call '{}' rejected: circuit breaker is open", instance);
		}
		else if (ex instanceof TimeoutException) {
			log.warn("AI provider call '{}' timed out", instance);
		}
		else {
			log.warn("AI provider call '{}' failed", instance, ex);
		}
		return new AssistantUnavailableException("AI provider unavailable (" + instance + ")", ex);
	}

}
