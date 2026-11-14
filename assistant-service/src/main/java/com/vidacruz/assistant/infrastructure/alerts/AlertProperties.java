package com.vidacruz.assistant.infrastructure.alerts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RF-06 settings ({@code assistant.alerts.*}).
 *
 * @param heartbeat    interval of the SSE comment that keeps idle connections open through proxies
 * @param clientBuffer alerts kept per client while it is slow to read; beyond that the oldest are dropped
 * @param maxAge       escalations older than this are not alerted (topic replays)
 */
@ConfigurationProperties("assistant.alerts")
public record AlertProperties(Duration heartbeat, int clientBuffer, Duration maxAge) {

	public AlertProperties {
		if (heartbeat == null || heartbeat.isZero() || heartbeat.isNegative()) {
			throw new IllegalArgumentException("assistant.alerts.heartbeat must be a positive duration");
		}
		if (clientBuffer < 1) {
			throw new IllegalArgumentException("assistant.alerts.client-buffer must be at least 1");
		}
		if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
			throw new IllegalArgumentException("assistant.alerts.max-age must be a positive duration");
		}
	}

}
