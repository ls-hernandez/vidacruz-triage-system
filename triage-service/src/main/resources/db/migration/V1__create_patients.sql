-- Patients registered at triage.
-- Enum-like columns store the Java enum name() in English; the Spanish contract values
-- (REGISTRADO, CRITICO, ...) are mapped only at the API / Kafka boundary.
-- Range CHECKs duplicate the API validation (RF-01) as a last line of defence.

CREATE TABLE patients (
    id                uuid          PRIMARY KEY,
    full_name         varchar(100)  NOT NULL,
    age               smallint      NOT NULL,
    heart_rate        smallint      NOT NULL,
    systolic_bp       smallint      NOT NULL,
    diastolic_bp      smallint      NOT NULL,
    spo2              smallint      NOT NULL,
    temperature       numeric(3, 1) NOT NULL,
    pain_level        smallint      NOT NULL,
    gcs               smallint      NOT NULL,
    chief_complaint   varchar(500)  NOT NULL,
    level             varchar(20)   NOT NULL,
    sla_minutes       integer       NOT NULL,
    status            varchar(20)   NOT NULL,
    escalated         boolean       NOT NULL DEFAULT false,
    escalated_at      timestamptz,
    registered_at     timestamptz   NOT NULL,
    status_changed_at timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0,

    CONSTRAINT chk_patients_full_name       CHECK (char_length(btrim(full_name)) BETWEEN 3 AND 100),
    CONSTRAINT chk_patients_age             CHECK (age BETWEEN 0 AND 120),
    CONSTRAINT chk_patients_heart_rate      CHECK (heart_rate BETWEEN 20 AND 250),
    CONSTRAINT chk_patients_systolic_bp     CHECK (systolic_bp BETWEEN 60 AND 260),
    CONSTRAINT chk_patients_diastolic_bp    CHECK (diastolic_bp BETWEEN 30 AND 160),
    CONSTRAINT chk_patients_blood_pressure  CHECK (diastolic_bp < systolic_bp),
    CONSTRAINT chk_patients_spo2            CHECK (spo2 BETWEEN 70 AND 100),
    CONSTRAINT chk_patients_temperature     CHECK (temperature BETWEEN 34.0 AND 42.0),
    CONSTRAINT chk_patients_pain_level      CHECK (pain_level BETWEEN 0 AND 10),
    CONSTRAINT chk_patients_gcs             CHECK (gcs BETWEEN 3 AND 15),
    CONSTRAINT chk_patients_chief_complaint CHECK (char_length(btrim(chief_complaint)) > 0),
    CONSTRAINT chk_patients_level           CHECK (level IN ('CRITICAL', 'URGENT', 'URGENT_2', 'NON_URGENT', 'NON_URGENT_2')),
    CONSTRAINT chk_patients_sla_minutes     CHECK (sla_minutes > 0),
    CONSTRAINT chk_patients_status          CHECK (status IN ('REGISTERED', 'IN_CONSULTATION', 'DISCHARGED')),
    CONSTRAINT chk_patients_escalated_at    CHECK (escalated = (escalated_at IS NOT NULL))
);

-- RF-03: the SLA scheduler only scans patients that can still be escalated.
CREATE INDEX idx_patients_escalation_candidates
    ON patients (registered_at)
    WHERE status = 'REGISTERED' AND escalated = false;

-- RF-05: operation report filters and groups by registration time.
CREATE INDEX idx_patients_registered_at ON patients (registered_at);
