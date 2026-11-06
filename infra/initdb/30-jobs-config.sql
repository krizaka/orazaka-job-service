-- ============================================================================
-- SOVKORA — Local DB bootstrap · 30 — JOB ORCHESTRATION + CONFIG PLANE
-- ----------------------------------------------------------------------------
-- The schema is NOT here any more (krizaka-internal#2255): the service creates it at startup with Flyway
-- (src/main/resources/db/migration/V1__job_baseline.sql, history table flyway_history_job).
-- The role and the database orazaka_db (shared with the conversation and governance contexts) come from the
-- workspace infra/initdb/00-reset.sql. Only the extension is created here (it needs an admin; the migration asserts it).
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS vector;
