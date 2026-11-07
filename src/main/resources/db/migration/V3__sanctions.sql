-- Sanctions data imported from OpenSanctions bulk files (spec §5.3, §6).
--
-- Versioning instead of separate staging tables: every row carries its dataset_version_id, and
-- screening only ever reads the version flagged is_active. A new import loads under its own
-- (LOADING) version, invisible to screening, and is made live by flipping is_active in a single
-- transaction. That is the atomic swap, and the previous version is still there for replay (M6).

CREATE TABLE sanctions_dataset_version (
    id            BIGSERIAL   PRIMARY KEY,
    source_url    TEXT        NOT NULL,
    version_label TEXT,                    -- upstream export version, e.g. 20261107135250-gvs
    checksum      TEXT,                    -- SHA-1 of the downloaded file (hex), as published upstream
    entity_count  BIGINT,
    name_count    BIGINT,
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    status        TEXT        NOT NULL
        CHECK (status IN ('LOADING', 'COMPLETED', 'SKIPPED', 'FAILED', 'PRUNED')),
    is_active     BOOLEAN     NOT NULL DEFAULT FALSE,
    error         TEXT,
    CHECK (NOT is_active OR status = 'COMPLETED')
);

-- At most one live dataset...
CREATE UNIQUE INDEX sanctions_dataset_version_one_active
    ON sanctions_dataset_version (is_active) WHERE is_active;
-- ...and at most one import in flight. Enforced by the database, so it holds across replicas.
CREATE UNIQUE INDEX sanctions_dataset_version_one_loading
    ON sanctions_dataset_version (status) WHERE status = 'LOADING';

CREATE TABLE sanctions_entity (
    id                 BIGSERIAL PRIMARY KEY,
    dataset_version_id BIGINT    NOT NULL REFERENCES sanctions_dataset_version (id) ON DELETE CASCADE,
    external_id        TEXT      NOT NULL,  -- OpenSanctions entity id (stable across versions)
    schema_type        TEXT      NOT NULL,  -- Person | Company | Organization | LegalEntity
    caption            TEXT      NOT NULL,
    birth_dates        TEXT[]    NOT NULL,  -- partial ISO dates as published: 1971, 1971-03, 1971-03-14
    countries          TEXT[]    NOT NULL,  -- country + jurisdiction + mainCountry (ISO codes, lower case)
    nationalities      TEXT[]    NOT NULL,  -- nationality + citizenship
    topics             TEXT[]    NOT NULL,
    source_lists       TEXT[]    NOT NULL,  -- OpenSanctions source datasets, e.g. gb_fcdo_sanctions
    program_ids        TEXT[]    NOT NULL,  -- sanction programmes, e.g. GB-RUS
    raw                JSONB     NOT NULL,  -- the entity exactly as published, for evidence and audit
    UNIQUE (dataset_version_id, external_id)
);

CREATE INDEX sanctions_entity_version_schema_idx ON sanctions_entity (dataset_version_id, schema_type);

-- One row per screenable name. The matcher's columns (normalised form, phonetic key, tokens) and
-- their trigram/phonetic indexes are added in M3, together with the normaliser that fills them.
CREATE TABLE sanctions_name (
    id                 BIGSERIAL PRIMARY KEY,
    entity_id          BIGINT    NOT NULL REFERENCES sanctions_entity (id) ON DELETE CASCADE,
    dataset_version_id BIGINT    NOT NULL,  -- denormalised so candidate retrieval filters without a join
    name_original      TEXT      NOT NULL,
    name_kind          TEXT      NOT NULL CHECK (name_kind IN ('PRIMARY', 'ALIAS', 'PREVIOUS')),
    is_alias           BOOLEAN   NOT NULL
);

CREATE INDEX sanctions_name_version_idx ON sanctions_name (dataset_version_id);
CREATE INDEX sanctions_name_entity_idx ON sanctions_name (entity_id);
