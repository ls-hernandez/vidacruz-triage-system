package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.application.AssistantUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * <p>
 * Each call that reaches the provider is timed in {@value #LATENCY_METRIC} with the tags {@code operation}
 * ({@value #EMBEDDING} / {@value #CHAT}) and {@code outcome} ({@value #SUCCESS} / {@value #ERROR}). The timer sits
 * between the time limit and the circuit breaker: a timeout is recorded as an error with the latency the user waited,
 * and a call rejected by an open circuit is not timed (it never reached the provider; Resilience4j counts it in its
 * own metrics).
 */
@Component
class ProviderGuard {

	static final String EMBEDDING = "embedding";
	static final String CHAT = "chat";

	static final String LATENCY_METRIC = "assistant.llm.latency";
	static final String SUCCESS = "success";
	static final String ERROR = "error";

	private static final Logger log = LoggerFactory.getLogger(ProviderGuard.class);

	private final CircuitBreakerRegistry circuitBreakers;
	private final TimeLimiterRegistry timeLimiters;
	private final MeterRegistry meterRegistry;

	ProviderGuard(CircuitBreakerRegistry circuitBreakers, TimeLimiterRegistry timeLimiters,
			MeterRegistry meterRegistry) {
		this.circuitBreakers = circuitBreakers;
		this.timeLimiters = timeLimiters;
		this.meterRegistry = meterRegistry;
		// all series exist in Prometheus before the first question
		for (String operation : new String[] { EMBEDDING, CHAT }) {
			latency(operation, SUCCESS);
			latency(operation, ERROR);
		}
	}

	<T> Mono<T> call(String instance, Callable<T> blockingCall) {
		CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(instance);
		TimeLimiter timeLimiter = timeLimiters.timeLimiter(instance);
		return Mono.fromCallable(blockingCall)
			.subscribeOn(Schedulers.boundedElastic())
			.transformDeferred(TimeLimiterOperator.of(timeLimiter))
			.transformDeferred(call -> timed(instance, call))
			.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
			.onErrorMap(ex -> !(ex instanceof AssistantUnavailableException), ex -> unavailable(instance, ex));
	}

	private <T> Mono<T> timed(String operation, Mono<T> call) {
		return Mono.defer(() -> {
			Timer.Sample sample = Timer.start(meterRegistry);
			return call.doOnSuccess(result -> sample.stop(latency(operation, SUCCESS)))
				.doOnError(ex -> sample.stop(latency(operation, ERROR)));
		});
	}

	/** Registers the timer on first use and returns the same instance afterwards. */
	private Timer latency(String operation, String outcome) {
		return Timer.builder(LATENCY_METRIC)
			.description("Latency of the calls to the AI provider (question embedding + search, or chat)")
			.tag("operation", operation)
			.tag("outcome", outcome)
			.register(meterRegistry);
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
