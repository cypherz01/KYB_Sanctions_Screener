-- Durable (L2) cache of raw external-source payloads (spec §6).
-- Rows are kept after expires_at: an expired row is the stale fallback during a source outage.
CREATE TABLE cache_entry (
    cache_key  TEXT        PRIMARY KEY,
    source     TEXT        NOT NULL,
    payload    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

-- Supports future housekeeping (purging long-expired rows) and per-source ops queries.
CREATE INDEX cache_entry_source_expires_idx ON cache_entry (source, expires_at);
