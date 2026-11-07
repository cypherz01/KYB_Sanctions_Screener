-- Matcher columns for sanctions names (spec §6, §8.2), filled by the Java normaliser at import.
-- Nullable because rows imported before this migration get them from the re-indexer, not SQL:
-- normalisation (ICU transliteration, Double Metaphone) only exists in Java and must be identical
-- for subjects and sanctions names.
ALTER TABLE sanctions_name
    ADD COLUMN name_normalised TEXT,
    ADD COLUMN tokens          TEXT[],
    ADD COLUMN name_phonetic   TEXT;   -- sorted Double Metaphone keys, e.g. "MHMT RXT"

-- Which normaliser produced a version's stored names; a mismatch triggers re-indexing.
ALTER TABLE sanctions_dataset_version ADD COLUMN normaliser_version TEXT;

-- Stage 1 retrieval: trigram similarity (the % operator) on the normalised name. pg_trgm compares
-- words independently, so token order does not matter.
CREATE INDEX sanctions_name_normalised_trgm_idx ON sanctions_name USING GIN (name_normalised gin_trgm_ops);

-- Stage 1 retrieval, second arm: exact phonetic-key match catches spellings trigrams miss
-- (Mohammed / Muhammad).
CREATE INDEX sanctions_name_phonetic_idx ON sanctions_name (dataset_version_id, name_phonetic);
