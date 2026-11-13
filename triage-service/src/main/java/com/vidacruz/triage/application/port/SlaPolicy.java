package com.vidacruz.triage.application.port;

import com.vidacruz.triage.domain.TriageLevel;

/** SLA per triage level (RN-02), in minutes. */
public interface SlaPolicy {

	int slaMinutesFor(TriageLevel level);

}
