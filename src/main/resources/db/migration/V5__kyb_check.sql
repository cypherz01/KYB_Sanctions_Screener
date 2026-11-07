-- KYB check results (spec §6, §11). Append-only: the application only inserts and reads.
-- The full response is stored exactly as returned, so GET /checks/{id} serves it unchanged.
-- M6 adds per-source snapshots and audit events alongside.
CREATE TABLE kyb_check (
    id                           UUID        PRIMARY KEY,
    company_number               TEXT        NOT NULL,
    requested_at                 TIMESTAMPTZ NOT NULL,
    completed_at                 TIMESTAMPTZ NOT NULL,
    decision                     TEXT        NOT NULL CHECK (decision IN ('PASS', 'REVIEW', 'FAIL')),
    score                        INTEGER     NOT NULL,
    -- The exact sanctions data screened against. Pruning deletes a version's rows but keeps this record.
    sanctions_dataset_version_id BIGINT      NOT NULL REFERENCES sanctions_dataset_version (id),
    scoring_config_version       TEXT        NOT NULL,
    engine_version               TEXT        NOT NULL,
    request                      JSONB       NOT NULL,
    response                     JSONB       NOT NULL
);

CREATE INDEX kyb_check_company_requested_idx ON kyb_check (company_number, requested_at DESC);
