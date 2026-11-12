-- Clinical audit trail (RN-04): append-only.
-- previous_status is NULL for the registration entry; escalation keeps REGISTERED -> REGISTERED
-- and is identified by event_type. RF-05 derives the first consultation time from this table.

CREATE TABLE audit_entries (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id      uuid        NOT NULL REFERENCES patients (id),
    previous_status varchar(20),
    new_status      varchar(20) NOT NULL,
    event_type      varchar(30) NOT NULL,
    occurred_at     timestamptz NOT NULL,

    CONSTRAINT chk_audit_entries_previous_status
        CHECK (previous_status IS NULL OR previous_status IN ('REGISTERED', 'IN_CONSULTATION', 'DISCHARGED')),
    CONSTRAINT chk_audit_entries_new_status
        CHECK (new_status IN ('REGISTERED', 'IN_CONSULTATION', 'DISCHARGED')),
    CONSTRAINT chk_audit_entries_event_type
        CHECK (event_type IN ('PATIENT_REGISTERED', 'PATIENT_IN_CONSULTATION', 'PATIENT_DISCHARGED', 'PATIENT_ESCALATED'))
);

-- RF-05 (first consultation per patient via ROW_NUMBER) and per-patient history.
CREATE INDEX idx_audit_entries_patient_occurred_at ON audit_entries (patient_id, occurred_at);

-- Append-only enforcement: any UPDATE, DELETE or TRUNCATE fails, whatever the client.
CREATE FUNCTION reject_audit_entries_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_entries is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_entries
    FOR EACH ROW EXECUTE FUNCTION reject_audit_entries_modification();

CREATE TRIGGER trg_audit_entries_no_truncate
    BEFORE TRUNCATE ON audit_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_entries_modification();
