-- Audit and replay (spec §6, §11): what each check saw, and everything that happened to it.

-- The exact data one check used, one row per source call. JSON, not JSONB: JSONB re-sorts object
-- keys, the mappers iterate some objects in order, and "exactly what the source returned" should
-- be literally true.
CREATE TABLE kyb_check_source_snapshot (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_id   UUID        NOT NULL REFERENCES kyb_check (id),
    source     TEXT        NOT NULL,
    operation  TEXT        NOT NULL,
    -- Null when the source could not be reached at all and nothing was cached (FCA UNAVAILABLE).
    fetched_at TIMESTAMPTZ,
    from_cache BOOLEAN     NOT NULL,
    stale      BOOLEAN     NOT NULL,
    payload    JSON        NOT NULL,
    UNIQUE (check_id, source, operation)
);

CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_id    UUID        NOT NULL REFERENCES kyb_check (id),
    event_type  TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    details     JSONB       NOT NULL
);

CREATE INDEX audit_event_check_idx ON audit_event (check_id, id);

-- Append-only is enforced here as well as by having no such code paths: an audit trail that only
-- the application's good behaviour protects is not much of one. TRUNCATE is deliberately left out,
-- so an operator (or a test) can still reset a whole environment; this stops mistakes, not a DBA.
CREATE FUNCTION reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % rejected', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER kyb_check_append_only
    BEFORE UPDATE OR DELETE ON kyb_check
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE TRIGGER kyb_check_source_snapshot_append_only
    BEFORE UPDATE OR DELETE ON kyb_check_source_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
