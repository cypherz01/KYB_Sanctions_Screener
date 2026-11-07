-- Baseline: extensions required by the sanctions name matcher (spec §8).
-- pg_trgm  : trigram similarity + GIN indexes for candidate retrieval
-- unaccent : diacritic stripping available inside SQL (the Java normaliser is authoritative)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
