package com.vidacruz.triage.application.port;

import com.vidacruz.triage.domain.AuditEntry;
import reactor.core.publisher.Mono;

/** Append-only clinical audit trail (RN-04): there is intentionally no update or delete. */
public interface AuditRepository {

	Mono<Void> append(AuditEntry entry);

}
