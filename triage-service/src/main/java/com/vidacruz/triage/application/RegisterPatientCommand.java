package com.vidacruz.triage.application;

import com.vidacruz.triage.domain.VitalSigns;

/** Already validated registration data (RF-01). */
public record RegisterPatientCommand(String fullName, int age, VitalSigns vitalSigns, String chiefComplaint) {
}
