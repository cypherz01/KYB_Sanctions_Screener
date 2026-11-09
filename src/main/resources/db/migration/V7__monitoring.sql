-- Ongoing monitoring (ADR-0004, ADR-0005, ADR-0006). A company is watched only after an explicit
-- opt-in. Stopping keeps the subscription record, which alerts refer to, and deletes the watched names.
CREATE TABLE monitoring_subscription (
    id              UUID        PRIMARY KEY,
    company_number  TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    latest_check_id UUID        NOT NULL REFERENCES kyb_check (id),
    next_recheck_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX monitoring_subscription_one_active
    ON monitoring_subscription (company_number) WHERE ended_at IS NULL;
CREATE INDEX monitoring_subscription_recheck_idx
    ON monitoring_subscription (next_recheck_at) WHERE ended_at IS NULL;

-- The index that reverse screening searches: who each subscription currently watches. It holds the
-- matcher's own columns, so a changed sanctions name is retrieved by the same rule as a forward screen.
CREATE TABLE monitoring_subject (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id UUID   NOT NULL REFERENCES monitoring_subscription (id) ON DELETE CASCADE,
    published_name  TEXT   NOT NULL,
    screened_name   TEXT   NOT NULL,
    subject_type    TEXT   NOT NULL,
    birth_date      TEXT,
    countries       TEXT[] NOT NULL,
    name_normalised TEXT   NOT NULL,
    name_phonetic   TEXT   NOT NULL
);

CREATE INDEX monitoring_subject_subscription_idx ON monitoring_subject (subscription_id);
CREATE INDEX monitoring_subject_trgm_idx ON monitoring_subject USING GIN (name_normalised gin_trgm_ops);
CREATE INDEX monitoring_subject_phonetic_idx ON monitoring_subject (name_phonetic);

-- The entities a subscription's latest check matched, so a removal or change can find who it affects.
CREATE TABLE monitoring_match (
    subscription_id UUID NOT NULL REFERENCES monitoring_subscription (id) ON DELETE CASCADE,
    external_id     TEXT NOT NULL,
    PRIMARY KEY (subscription_id, external_id)
);

CREATE INDEX monitoring_match_external_idx ON monitoring_match (external_id);

-- One row per activated dataset, written in the transaction that activates it, so a crash between
-- activation and screening cannot lose the trigger.
CREATE TABLE monitoring_run (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset_version_id BIGINT      NOT NULL REFERENCES sanctions_dataset_version (id) ON DELETE CASCADE,
    created_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ
);

CREATE INDEX monitoring_run_pending_idx ON monitoring_run (id) WHERE finished_at IS NULL;

-- A single row: what the watched subjects were last screened against. Not a foreign key, because the
-- version it names may be pruned, and that is exactly the case that forces a full re-screen.
CREATE TABLE monitoring_state (
    id                      BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    last_dataset_version_id BIGINT,
    fingerprint             TEXT
);

INSERT INTO monitoring_state DEFAULT VALUES;

-- Work for the background workers, claimed with FOR UPDATE SKIP LOCKED.
CREATE TABLE monitoring_job (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id UUID        NOT NULL REFERENCES monitoring_subscription (id) ON DELETE CASCADE,
    trigger         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- A subscription needs one pending re-check at most, however many things asked for it.
CREATE UNIQUE INDEX monitoring_job_one_per_subscription ON monitoring_job (subscription_id);
CREATE INDEX monitoring_job_due_idx ON monitoring_job (next_attempt_at);

-- Immutable events with no status: a lifecycle would be the analyst case workflow, which is out of scope.
CREATE TABLE monitoring_alert (
    id                UUID        PRIMARY KEY,
    subscription_id   UUID        NOT NULL REFERENCES monitoring_subscription (id),
    company_number    TEXT        NOT NULL,
    check_id          UUID        NOT NULL REFERENCES kyb_check (id),
    previous_check_id UUID        NOT NULL REFERENCES kyb_check (id),
    type              TEXT        NOT NULL CHECK (type IN ('MATCHES_CHANGED', 'MATCH_CLEARED', 'DECISION_CHANGED')),
    trigger           TEXT        NOT NULL,
    decision_before   TEXT        NOT NULL,
    decision_after    TEXT        NOT NULL,
    matches_added     JSONB       NOT NULL,
    matches_removed   JSONB       NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX monitoring_alert_recent_idx ON monitoring_alert (occurred_at DESC, id);

CREATE TRIGGER monitoring_alert_append_only
    BEFORE UPDATE OR DELETE ON monitoring_alert
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- The transactional outbox: written with the alert, so an alert cannot exist without its delivery or
-- the reverse. The body is stored as sent, so a retry signs and sends the same bytes.
CREATE TABLE webhook_outbox (
    alert_id        UUID        PRIMARY KEY REFERENCES monitoring_alert (id),
    body            TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ,
    last_error      TEXT
);

CREATE INDEX webhook_outbox_due_idx ON webhook_outbox (next_attempt_at) WHERE status = 'PENDING';
