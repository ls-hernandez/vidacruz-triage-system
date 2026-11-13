package com.vidacruz.triage.infrastructure.web.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Requires the {@code X-API-Key} header on every endpoint except the health probes ({@code /actuator/health/**}).
 * A missing or wrong key is a 401 {@link ProblemDetail} in Spanish, like the other API errors. The key is compared in
 * constant time ({@link ApiKey}) and never logged.
 * <p>
 * A plain {@link WebFilter} instead of Spring Security: there is a single rule (one header, one public path), and
 * Spring Security would add form login, CSRF, sessions and HTTP Basic defaults that would all have to be disabled
 * (docs/assumptions.md, 33).
 */
class ApiKeyAuthenticationFilter implements WebFilter, Ordered {

	static final String HEADER = "X-API-Key";
	static final String TITLE = "No autenticado";
	static final String MISSING_KEY_DETAIL = "Falta el header X-API-Key.";
	static final String INVALID_KEY_DETAIL = "La clave del header X-API-Key no es válida.";

	private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

	private static final List<PathPattern> PUBLIC_PATHS = List.of(
			PathPatternParser.defaultInstance.parse("/actuator/health"),
			PathPatternParser.defaultInstance.parse("/actuator/health/**"));

	private final ApiKey apiKey;
	private final ObjectMapper objectMapper;

	ApiKeyAuthenticationFilter(ApiKey apiKey, ObjectMapper objectMapper) {
		this.apiKey = apiKey;
		this.objectMapper = objectMapper;
	}

	@Override
	public int getOrder() {
		// before any other filter; the HTTP server observation (traceId) is opened earlier by the web handler adapter
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		if (isPublic(request.getPath().pathWithinApplication())) {
			return chain.filter(exchange);
		}
		String presented = request.getHeaders().getFirst(HEADER);
		if (presented == null || presented.isEmpty()) {
			return reject(exchange, MISSING_KEY_DETAIL);
		}
		if (!apiKey.matches(presented)) {
			return reject(exchange, INVALID_KEY_DETAIL);
		}
		return chain.filter(exchange);
	}

	/**
	 * Health probes only. A path with {@code .} or {@code ..} segments (also percent-encoded) is never public, so
	 * {@code /actuator/health/../prometheus} cannot skip the key even if something downstream normalized it.
	 */
	static boolean isPublic(PathContainer path) {
		boolean dotSegments = path.elements()
			.stream()
			.filter(PathContainer.PathSegment.class::isInstance)
			.map(segment -> ((PathContainer.PathSegment) segment).valueToMatch())
			.anyMatch(value -> value.equals(".") || value.equals(".."));
		return !dotSegments && PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
	}

	private Mono<Void> reject(ServerWebExchange exchange, String detail) {
		ServerHttpRequest request = exchange.getRequest();
		log.warn("Rejected {} {}: {}", request.getMethod(), request.getPath().value(), detail);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
		problem.setTitle(TITLE);
		problem.setInstance(URI.create(request.getPath().value()));

		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatus.UNAUTHORIZED);
		response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + HEADER + "\"");
		return response.writeWith(Mono.fromCallable(() -> response.bufferFactory().wrap(toJson(problem))));
	}

	private byte[] toJson(ProblemDetail problem) throws JsonProcessingException {
		return objectMapper.writeValueAsBytes(problem);
	}

}
