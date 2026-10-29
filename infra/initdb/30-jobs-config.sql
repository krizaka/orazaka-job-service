-- ============================================================================
-- ORAZAKA — Local DB bootstrap · 30 — JOB ORCHESTRATION + CONFIG PLANE
-- ----------------------------------------------------------------------------
-- Future owner: Job Orchestration service — async COMMAND lifecycle (jobs,
-- outbox, consumer dedup, routing rules) plus the DB-driven config plane it
-- keeps hosting (model catalog, providers, capabilities, pipeline/validation
-- configs, runtime config — ADR-027/029/030/031; extraction deferred).
-- user_id columns are OPAQUE ActorIds — no FK into the identity context.
-- ============================================================================

CREATE TABLE orazaka_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255),
    feature_key VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    payload JSONB,
    result JSONB,
    error_message TEXT,
    -- What the job's material is, declared by its producer. No DEFAULT, on purpose: this plane's
    -- retention window is chosen by the class, and a default would be a class nobody declared
    -- (ADR-065).
    data_class VARCHAR(20) NOT NULL
        CONSTRAINT ck_orazaka_jobs_data_class CHECK (data_class IN ('STANDARD','SENSITIVE','REGULATED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_orazaka_jobs_user_id ON orazaka_jobs(user_id);
-- What the retention sweep reads, and only that: protected jobs that have reached a terminal state.
CREATE INDEX idx_orazaka_jobs_protected_terminal ON orazaka_jobs(updated_at)
    WHERE data_class <> 'STANDARD' AND status IN ('COMPLETED','FAILED');

-- Which protected jobs the retention sweep purged — the ids and when, never what they held
-- (ADR-065). An execution can outlive its job's terminal status: a timed-out execution is not
-- cancelled and may still write its output, so a purged job's directory can come back. While its
-- tombstone lives, the sweep deletes it again; the tombstone itself ages out.
CREATE TABLE orazaka_job_purge (
    job_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255),
    purged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Transactional outbox (AGENTS.md §6): producers append rows inside their business
-- transaction; the relay publishes to RabbitMQ with message_id as the AMQP messageId
-- (consumer-side idempotency key) and marks published_at. Exponential backoff via
-- attempts/next_attempt_at on publish failure.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    exchange VARCHAR(100) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    message_id UUID NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON outbox_events(next_attempt_at) WHERE published_at IS NULL;

-- Consumer-side idempotency (AGENTS.md §6): each consumer records the AMQP messageId of
-- messages it has fully processed; redeliveries and dual-publishes during migration are
-- skipped. One row per (consumer, message); purged after the retention window. Shared by
-- co-located consumers until each service owns its database (then each carries its copy).
CREATE TABLE processed_messages (
    consumer VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, message_id)
);

-- Tier-aware dynamic dispatch rules read at job submission (target §6 routing keys).
CREATE TABLE orazaka_routing_rules (
    id UUID PRIMARY KEY,
    rule_key VARCHAR(50) NOT NULL UNIQUE,
    feature_key VARCHAR(50) NOT NULL,
    user_tier VARCHAR(50) NOT NULL,
    target_routing_key VARCHAR(100) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE
);

-- Worker registry (ADR-038, S3). A worker declares itself here at boot and heartbeats; the
-- platform learns which families are actually served without any service naming a worker.
--
-- ADVISORY, NOT AUTHORISING. Registration is an availability signal, never a permission: a worker
-- that cannot reach this table must still consume, or an observability feature becomes an outage.
-- Dispatch therefore never consults this table — routing_key alone decides where a job goes.
--
-- NOTHING READS `status` IN THIS PHASE. Phase F turns it into capability availability that a user
-- can see (a Studio greys out rather than charging for a doomed run). It is recorded now so that
-- adding that behaviour later is a read, not a migration — the same reason regulatory_class was
-- placed empty in 80-studio.sql.
CREATE TABLE worker_registry (
    worker_name   VARCHAR(80)  PRIMARY KEY,
    worker_family VARCHAR(60)  NOT NULL,   -- joins orazaka_capabilities.worker_family, by name only
    bindings      JSONB        NOT NULL,   -- ["job.video.*", "job.compose.*"] — what it drains
    version       VARCHAR(30)  NOT NULL,
    concurrency   INT          NOT NULL DEFAULT 1,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        VARCHAR(20)  NOT NULL DEFAULT 'HEALTHY',
    CONSTRAINT ck_worker_registry_status CHECK (status IN ('HEALTHY','STALE','DRAINING'))
);
-- Partial index: the only question asked of this table is "which families are served right now".
CREATE INDEX idx_worker_family ON worker_registry(worker_family) WHERE status = 'HEALTHY';

-- The model categories whose jobs an EXTERNAL worker runs (the media worker, on a GPU or the Mac): the queue their jobs wait in.
-- A model of such a category is runnable only while a HEALTHY worker above drains that queue — otherwise a run waits for
-- « a machine » for ever (dev, 2026-10-20). Categories the job service runs itself are not listed: nothing is checked for
-- them, the registry being advisory (ADR-038). Data, so a new worker-run category is a row, not a release.
CREATE TABLE IF NOT EXISTS model_category_queue (
    category    VARCHAR(50)  NOT NULL,
    -- true: a model a provider hosts (its jobs are routed by a cheap CPU worker that only calls the provider's API);
    -- false: a model on our own hardware (a GPU worker). Two queues, so a GPU worker that is off never greys out the
    -- hosted model and a GPU job is never taken by a worker that cannot run it.
    hosted      BOOLEAN      NOT NULL DEFAULT FALSE,
    routing_key VARCHAR(120) NOT NULL CHECK (routing_key LIKE 'job.%'),
    PRIMARY KEY (category, hosted)
);
INSERT INTO model_category_queue (category, hosted, routing_key) VALUES
    ('video', FALSE, 'job.video.generate'),
    ('video', TRUE,  'job.hostedvideo.generate'),
    ('music', FALSE, 'job.sound.generate'),
    ('foley', FALSE, 'job.sound.generate')
ON CONFLICT (category, hosted) DO NOTHING;

-- ── Config plane ─────────────────────────────────────────────────────────────

-- Operation-graph capability registry: blueprint (label/icon/routing) + enabled state.
-- Single source of truth superseding the former orazaka.features.* yaml AND the
-- orazaka_feature_flags table (both removed). Read by GraphEngine via the
-- CapabilityProvider outbound port.
CREATE TABLE orazaka_capabilities (
    feature_key      VARCHAR(255) PRIMARY KEY,
    -- label and icon were here, and uri_path, http_method and payload_template below them. All
    -- five are gone (ADR-069 §5). They were this table's UI-MANIFEST half: what a button said,
    -- what it drew, where it POSTed and what body it sent. M3 deleted the endpoints they named and
    -- served the chat bar's row from Studios instead, so a capability's display name now lives in
    -- the pack's i18n where a pack author writes it; §4 replaced the one column that carried
    -- contract meaning (payload_template) with a real schema. What is left is dispatch and
    -- contract: WHERE the work runs, WHICH code path runs it, WHAT it accepts and publishes, WHAT
    -- it bills, WHICH lane it waits in, and whether it is on.
    handler_key      VARCHAR(100) NOT NULL, -- dispatch discriminant: JobListener routes to the JobExecutionStrategy whose handlerKey() equals this
    -- ADR-037 (S1): WHERE this capability's work runs. Producers used to derive it from a
    -- contains() chain over feature_key, so a capability the chain did not recognise was
    -- silently published to the text queue. The mapping is data; there is no default.
    routing_key      VARCHAR(120) NOT NULL,
    -- worker_family was here. It is gone (ADR-038 follow-up): the only coherent reading of it is
    -- "the family segment of routing_key", which makes it derivable — and the seeded values
    -- disagreed with that reading on the two capabilities served by the Python worker
    -- (job.video.generate and job.compose.assemble were both labelled 'media'). A column that is
    -- redundant where it is right and wrong where it is not cannot be the basis of availability.
    -- Phase F reads worker_registry.bindings against routing_key, which answers exactly and is
    -- what [EXEC-002] already checks. Workers still declare a `family` in worker.yaml: there it is
    -- a self-description, not a claim about which capabilities it serves.
    -- The unit consumption is measured in before the pricebook converts it to credits. NULL where
    -- the unit is a property of the MODEL and not of the capability: credit_pricebook keeps `unit`
    -- out of its key precisely because TTS bills KILOCHAR and STT bills AUDIO_MINUTE under the
    -- same AUDIO capability (70-billing.sql). Nothing reads this column yet.
    billable_unit    VARCHAR(30),
    -- WHAT this bills as: the BillableCapability the pricebook is keyed by (ADR-033). Added in
    -- phase C so JobMeteringService can read the answer instead of re-deriving it from a
    -- contains() chain over feature_key — the last such chain on the money path (ADR-038).
    -- Values below are a TRANSCRIPTION of what that chain returned, so the change is provably
    -- behaviour-preserving; one of them is wrong and is flagged where it is seeded.
    -- EXTERNAL_CHECK (orazaka-packs#138): a call to a provider outside the engine, per CALL. The
    -- constraint is named so the EXTERNAL CHECKS block below can widen an existing database's.
    billable_capability VARCHAR(20) NOT NULL DEFAULT 'CHAT'
                        CONSTRAINT ck_orazaka_capabilities_billable_capability
                        CHECK (billable_capability IN ('CHAT','IMAGE','AUDIO','VIDEO','AGENT','EXTERNAL_CHECK','NONE')),
    -- WHICH LANE this capability's work waits in (ADR-067). Two classes, never three, and they name
    -- the USER'S RELATIONSHIP TO THE WAIT rather than a rank — nobody argues for a promotion to a
    -- class that means "I am waiting":
    --   INTERACTIVE — the duration is bounded by the model's own speed: a turn, one image to
    --                 describe, a sentence to speak.
    --   BATCH       — the duration follows what the user supplied or asked to produce: a generated
    --                 image or video, an assembly, a file of arbitrary length to transcribe.
    -- Measured on this machine from orazaka_jobs, not guessed: image generation p50 67.1 s (n=4),
    -- image analysis p50 4.9 s (n=16), chat completion p50 1.1 s (n=97). The rest are classed by
    -- the rule above and flagged as unmeasured where they are.
    -- A lane is a QUEUE, and this column must agree with the one its routing_key feeds —
    -- LaneCoherenceRules fails the build when it does not, which is what keeps this from being the
    -- decoration `billable_unit NULL` was.
    -- DEFAULT 'BATCH', and that is the fail-closed direction: a pack CONTRIBUTES capabilities
    -- through CapabilityRegistryService and its manifest has no field for this — a pack that chose
    -- its own lane would choose INTERACTIVE, and everything would be interactive. An unclassified
    -- capability therefore waits where waiting is expected; the platform reclassifies it from
    -- measured durations, the way these rows were classed.
    latency_class    VARCHAR(20) NOT NULL DEFAULT 'BATCH'
                     CONSTRAINT ck_orazaka_capabilities_latency_class
                     CHECK (latency_class IN ('INTERACTIVE','BATCH')),
    -- ── THE CAPABILITY CONTRACT (ADR-069) ────────────────────────────────────────────────────
    -- Two halves, because a capability has always had two and only one was ever written down.
    --
    -- input_schema  — what a CALLER MAY PASS: a real JSON Schema, with types, `required`, defaults
    --                 and the `format: prose | asset-id` M3 introduced so a composer knows which
    --                 of its two holdings fills an input (ADR-068 §3). This is what
    --                 payload_template carried as a string of ${placeholders} with no types, no
    --                 requiredness and no way to check it.
    -- output_schema — what a SUCCESSFUL EXECUTION PUBLISHES, with types. Nothing declared this
    --                 before, which is why every {{steps.<out>.<field>}} link in every blueprint
    --                 referenced a field no contract mentioned and no rule could check. The values
    --                 below were a hand-written map inside BlueprintFitnessTest — and the map was
    --                 WRONG about audio analysis (it said `url`; the executor publishes
    --                 `analysis`), transcribed while that row still called itself "Audio
    --                 Generation". A transcription is a copy that rots; this is the declaration.
    --
    -- Both are `{}` by default, and that is the fail-closed direction for a pack: an undeclared
    -- contract matches nothing, so a blueprint step over an undeclared capability fails the
    -- fitness function instead of passing silently. A pack declares them in its manifest
    -- (`requires.capabilities[].inputSchema` / `.outputSchema`) and the installer writes them,
    -- exactly like routing_key, billable_unit and latency_class before them.
    input_schema     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    output_schema    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- WHETHER THIS CAPABILITY DISPATCHES. Re-documented in ADR-069 §5, because what it meant
    -- changed under it: before M3 a disabled row also removed a button from the chat composer, so
    -- the flag conflated "the platform will not run this" with "do not offer this". The composer's
    -- row comes from Studios now and an actor's access comes from entitlement, so exactly one
    -- meaning is left: a disabled capability is NOT DISPATCHED — GraphEngine renders it Invisible,
    -- a blueprint step naming it fails the fitness function ([ADR-034 §15.2]), and nothing routes
    -- its work. It is the switch for "this platform cannot currently run this", which is why the
    -- prober's degraded-mode state is reported separately and never written here.
    is_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- job.{capability}.{action} is the whole of the job plane's key grammar (AGENTS.md §6): a key
    -- outside it binds to no queue, and a message published to no queue is discarded by the
    -- broker without an error — the same silent loss this table was introduced to end.
    -- ck_orazaka_capabilities_endpoint was here: uri_path and http_method had to be NULL or
    -- present together, a half-endpoint being refused. Both columns are gone (ADR-069 §5).
    CONSTRAINT ck_orazaka_capabilities_routing_key CHECK (routing_key LIKE 'job.%')
);

CREATE TABLE orazaka_models (
    id SERIAL PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL UNIQUE,
    model_label VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    options VARCHAR(1000),
    is_default BOOLEAN DEFAULT FALSE,
    provider_name VARCHAR(255) DEFAULT 'ollama',
    max_steps INT,
    recommended_fps INT,
    supported_hardware VARCHAR(255),
    description TEXT,
    -- A speech voice's language, declared (ADR-076): the voice turn picks the voice of the turn's
    -- locale from here, never from the name or the options. NULL for everything that is not a
    -- voice, and for a voice that serves no one locale (tts-1).
    locale VARCHAR(16),
    -- The voice a locale gets when the caller names none. One per (category, locale), enforced
    -- below; independent of is_default, which stays the category's default for the job plane.
    locale_default BOOLEAN NOT NULL DEFAULT FALSE,
    -- The registry metadata the marketing site and the docs publish (krizaka/orazaka scripts/generate-docs.mjs
    -- reads it; nothing about a model is typed anywhere else). Routing never reads these columns.
    vendor VARCHAR(120),            -- who trained the base model
    family VARCHAR(120),            -- the model line it belongs to
    capabilities VARCHAR(200),      -- comma list: chat, reasoning, vision, code, embeddings, image, video, speech-to-text, text-to-speech
    runs_on VARCHAR(16),            -- 'local' (the operator's hardware) | 'provider' (a hosted API, opt-in)
    runtime VARCHAR(160),           -- what serves it: Ollama, stable-diffusion.cpp · Metal, CUDA, a provider…
    open_weights BOOLEAN,           -- are the weights published (under whatever licence)
    licence VARCHAR(160),           -- the licence of the model's own card
    licence_url VARCHAR(400),
    commercial_use VARCHAR(12),     -- 'yes' | 'conditions' (a community licence, share-alike, use restrictions) | 'no' (non-commercial)
    context_window INT,             -- tokens, from the model card; NULL when it is not a text model or unknown
    status VARCHAR(16) NOT NULL DEFAULT 'internal',  -- available | planned | internal (in the catalogue, never published)
    -- What a form's model picker shows beside the name (measured on this setup, docs/measured-runs; the price from
    -- the provider's rate card). Never typed in a client: the Studio form is built from these columns.
    typical_seconds INT,            -- typical seconds for one run; NULL while unmeasured
    price_cents INT,                -- US cents for one run; NULL when free (the operator's own hardware)
    CONSTRAINT ck_models_runs_on CHECK (runs_on IS NULL OR runs_on IN ('local', 'provider')),
    CONSTRAINT ck_models_commercial_use CHECK (commercial_use IS NULL OR commercial_use IN ('yes', 'conditions', 'no')),
    CONSTRAINT ck_models_status CHECK (status IN ('available', 'planned', 'internal')),
    CONSTRAINT ck_models_measures CHECK ((typical_seconds IS NULL OR typical_seconds >= 0) AND (price_cents IS NULL OR price_cents >= 0)),
    CONSTRAINT ck_models_locale_default_has_locale CHECK (NOT locale_default OR locale IS NOT NULL)
);
CREATE UNIQUE INDEX ux_models_locale_default ON orazaka_models (category, locale) WHERE locale_default;

CREATE TABLE ai_providers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    base_url VARCHAR(255) NOT NULL,
    api_key VARCHAR(255)
);

CREATE TABLE pipeline_interceptor_config (
    id              SERIAL       PRIMARY KEY,
    interceptor_key VARCHAR(100) NOT NULL UNIQUE,
    display_label   VARCHAR(200) NOT NULL,
    execution_order INTEGER      NOT NULL DEFAULT 0,
    is_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_pipeline_interceptor_config_key ON pipeline_interceptor_config (interceptor_key);
CREATE INDEX idx_pipeline_interceptor_config_order ON pipeline_interceptor_config (execution_order);

-- The semantic cache's answers (ai-engine#40). Kept identical to orazaka-ai-engine
-- orazaka-interceptors/src/main/resources/db/semantic-cache.sql and to the workspace migration
-- infra/migrations/2026-10-22-semantic-cache.sql.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS semantic_cache_entry (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    scope_guard_hash CHAR(64)     NOT NULL,
    model_tier       VARCHAR(100) NOT NULL,
    exact_key        CHAR(64)     NOT NULL,
    embedding        vector(1024),                 -- NULL for an entry kept for the exact tier only
    signature        TEXT         NOT NULL,        -- the turn's numbers and negations (TurnSignature)
    response         TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_semantic_cache_key UNIQUE (tenant_id, scope_guard_hash, model_tier, exact_key)
);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_expiry ON semantic_cache_entry (expires_at);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding
    ON semantic_cache_entry USING hnsw (embedding vector_cosine_ops);

CREATE TABLE validation_pipeline_configs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    step_type             VARCHAR(20) NOT NULL
                          CHECK (step_type IN ('STRUCTURAL_A','SANDBOX_B','SEMANTIC_C','TDR_D')),
    is_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    execution_order       INT NOT NULL DEFAULT 0,
    configuration_payload JSONB NOT NULL DEFAULT '{}',
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_validation_step_type UNIQUE (step_type)
);
CREATE INDEX idx_validation_pipeline_execution_order ON validation_pipeline_configs (execution_order);

-- ── Dynamic runtime config (ADR-031) ─────────────────────────────────────────
-- DB = source of truth for RUNTIME behaviour toggles/tuning read AFTER startup (an admin
-- changes these live, no redeploy). NOT for bootstrap/infra config (datasource, broker tuning,
-- timeouts, cache headers, vector-store TYPE) — those must stay in application.yml because they
-- are needed before the DB/broker/web layer exist. Read Spring-first via a typed
-- RuntimeConfigProvider port + persistence adapter (mirrors RateLimitProvider / CapabilityProvider).
CREATE TABLE orazaka_runtime_config (
    config_key   VARCHAR(120) PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    value_type   VARCHAR(20)  NOT NULL DEFAULT 'string',  -- string | boolean | int
    description  TEXT
);

-- ============================================================================
-- JOB ORCHESTRATION + CONFIG PLANE SEED DATA
-- ============================================================================

INSERT INTO orazaka_routing_rules (id, rule_key, feature_key, user_tier, target_routing_key, is_active) VALUES
('550e8400-e29b-41d4-a716-446655440004', 'img_gen_free', 'IMAGE_GEN', 'free', 'job.media.generate', TRUE),
('550e8400-e29b-41d4-a716-446655440005', 'img_gen_premium', 'IMAGE_GEN', 'premium', 'job.media.generate', TRUE),
('550e8400-e29b-41d4-a716-446655440006', 'vid_gen_free', 'VIDEO_GEN', 'free', 'job.video.generate', TRUE),
('550e8400-e29b-41d4-a716-446655440007', 'vid_gen_premium', 'VIDEO_GEN', 'premium', 'job.video.generate', TRUE)
ON CONFLICT (rule_key) DO NOTHING;

INSERT INTO ai_providers (name, base_url, api_key) VALUES
('ollama', 'http://localhost:11434', NULL),
('localai', 'http://localhost:8085', 'not-required'),
('localai-video', 'http://localhost:8188', NULL),
('localai-image', 'http://localhost:8086/v1', 'not-required'),
('do-inference', 'https://inference.do-ai.run', 'not-required'),
-- The image client of Spring AI appends /images/generations to the base URL (the local sd-server's is .../v1): the same endpoint as do-inference, with the /v1.
('do-inference-images', 'https://inference.do-ai.run/v1', 'not-required')
ON CONFLICT (name) DO NOTHING;

INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default, max_steps, recommended_fps, supported_hardware) VALUES
-- Speech Models
('piper-en-low', 'Piper Low (en)', 'speech', 'Ryan,Low', 'localai', FALSE, NULL, NULL, NULL),
('piper-en-medium-ljspeech', 'Piper LJSpeech (en)', 'speech', 'LJSpeech,Medium', 'localai', TRUE, NULL, NULL, NULL),
-- Ryan's dataset is non-commercial (CC BY-NC-SA 4.0, its model card): kept, never a default — a firm that sells gets a voice it may use.
('piper-en-medium-ryan', 'Piper Ryan (en)', 'speech', 'Ryan,Medium', 'localai', FALSE, NULL, NULL, NULL),
('piper-fr-medium', 'Piper Medium (fr)', 'speech', 'Medium,Fr', 'localai', FALSE, NULL, NULL, NULL),
-- The two fr-FR voices of the voice turn (ADR-076): siwis (female, CC-BY 4.0) and tom (male,
-- AGPL-3.0 weights, used unmodified and never redistributed by this repository).
('piper-fr-siwis-medium', 'Piper Siwis (fr-FR, féminine)', 'speech', 'Medium,Fr', 'localai', FALSE, NULL, NULL, NULL),
('piper-fr-tom-medium', 'Piper Tom (fr-FR, masculine)', 'speech', 'Medium,Fr', 'localai', FALSE, NULL, NULL, NULL),
('tts-1', 'OpenAI TTS-1', 'speech', 'Alloy,Echo,Fable,Onyx,Nova,Shimmer', 'localai', FALSE, NULL, NULL, NULL),
-- Image Models — what sd-server (stable-diffusion.cpp, Metal, :8086) can actually serve. Each
-- model_name is the id of an install recipe of the orazaka CLI (`orazaka models image`), which
-- launches the DEFAULT row and nothing else: sd-server serves one model at a time, and a row it
-- cannot serve would put the wrong model's name on the image and on the bill. max_steps is the
-- model's own step count — the engine sends it with every request and IMAGE_STEP bills it.
-- Licences allow commercial use: SDXL Turbo (non-commercial research licence) is not listed.
('flux-2-klein-4b', 'FLUX.2 [klein] 4B', 'image', NULL, 'localai-image', TRUE, 4, NULL, NULL),
('z-image-turbo', 'Z-Image Turbo', 'image', NULL, 'localai-image', FALSE, 8, NULL, NULL),
('v1-5-pruned-emaonly', 'Stable Diffusion 1.5', 'image', NULL, 'localai-image', FALSE, 20, NULL, NULL),
-- Hosted: Stable Diffusion 3.5 Large on DigitalOcean serverless inference ($0.08 an image), routed by Orazaka — no declared steps (ImageGeneratorClientImpl).
('stable-diffusion-3.5-large', 'Stable Diffusion 3.5 Large (DigitalOcean)', 'image', NULL, 'do-inference-images', FALSE, NULL, NULL, NULL),
-- Video Models
-- SVD-XT is HEAVY (needs >64GB unified memory); kept in the catalog but NOT the default.
-- The worker's memory preflight fails it cleanly on a 64GB Mac (§12: honest failure).
('stable-video-diffusion-img2vid-xt', 'Stable Video Diffusion XT (heavy, >64GB)', 'video', NULL, 'localai-video', FALSE, 25, 14, 'cuda'),
-- AnimateDiff-Lightning fits a 64GB Mac on MPS — the local-dev default video model.
('animatediff-lightning-mps', 'AnimateDiff Lightning (MPS)', 'video', NULL, 'localai-video', TRUE, 8, 12, 'mps'),
('apple-coreml-video-pipeline', 'Apple CoreML Video Pipeline', 'video', NULL, 'localai-video', FALSE, NULL, NULL, 'mps'),
('mlx-animatediff-lightning', 'MLX Native AnimateDiff', 'video', NULL, 'localai-video', FALSE, 8, 12, 'APPLE_SILICON_MLX'),
('mlx-stable-diffusion-video', 'MLX Native Video', 'video', NULL, 'localai-video', FALSE, 25, 14, 'APPLE_SILICON_MLX'),
('stable-video-diffusion-img2vid-xt-mps-fp32', 'SVD XT (PyTorch MPS Float32)', 'video', NULL, 'localai-video', FALSE, 25, 14, 'APPLE_SILICON_MLX'),
-- Wan 2.x (Apache-2.0) — the 9:16 video of the hourly GPU host (ADR-077), served by the CUDA image of
-- orazaka-worker-media. supported_hardware stays NULL: the core does not run them, a worker does, and
-- the worker refuses by name a model it cannot serve; the HOST check would grey them out on every core
-- that is not itself a GPU machine (dev runs on App Platform). recommended_fps is the model's own.
('wan2.2-ti2v-5b', 'Wan 2.2 TI2V 5B (9:16, GPU)', 'video', NULL, 'localai-video', FALSE, 40, 24, NULL),
('wan2.1-t2v-1.3b', 'Wan 2.1 T2V 1.3B (9:16, GPU)', 'video', NULL, 'localai-video', FALSE, 30, 16, NULL),
-- Wan 2.2 A14B on our own 80 GB card at 720p (blueprint video-generation 1.4.0, orazaka-worker-media models.yaml): the same model as the serverless one below, made here.
('wan2.2-t2v-a14b', 'Wan 2.2 T2V A14B (720p, 80 GB GPU)', 'video', NULL, 'localai-video', FALSE, 40, 16, NULL),
('wan2.2-i2v-a14b', 'Wan 2.2 I2V A14B (720p, 4-step distill)', 'video', NULL, 'localai-video', FALSE, 4, 16, NULL),
-- The image-to-video bake-off's winners (docs/_generated/MEASURED_RUNS.md, krizaka/orazaka-worker-media models.yaml `kit`): LTX-Video 0.9.8 for landscape and iteration,
-- the same I2V A14B checkpoint at its full 40-step schedule for people in 9:16.
('ltx-video-0.9.8-13b-distilled', 'LTX-Video 0.9.8 13B distilled (720p, GPU)', 'video', NULL, 'localai-video', FALSE, 8, 24, NULL),
('wan2.2-i2v-a14b-hq', 'Wan 2.2 I2V A14B (720p, 40 steps)', 'video', NULL, 'localai-video', FALSE, 40, 16, NULL),
-- Wan 2.2 A14B is made by the provider `do-inference` (DigitalOcean serverless) and run by Orazaka: the media worker routes it (orazaka-worker-media, app/remote_video.py).
('wan2-2-t2v-a14b', 'Wan 2.2 T2V A14B (serverless)', 'video', NULL, 'do-inference', FALSE, 0, 16, NULL),
-- The optional upscale step of the video Studio (orazaka-worker-media app/upscale.py, blueprint video-generation 1.6.0): SeedVR2 3B, Apache-2.0, run by the same GPU worker after the clip. Never a default: the Studio's `upscale` input is none until asked.
('seedvr2-3b', 'SeedVR2 3B (video upscale, GPU)', 'video-upscale', NULL, 'localai-video', FALSE, NULL, NULL, NULL),
-- Vision Models
-- The vision DEFAULT is llava, not llama3.2-vision (ADR-042). The latter is the better model and
-- was the seeded default, but its `mllama` architecture is not supported by the llama-server this
-- platform pins: every image analysis died with "unknown model architecture: 'mllama'" — a default
-- that cannot load is a default that makes the capability look broken. llava loads and analyses
-- (measured: 787 and 744 tokens on a real run). llama3.2-vision stays in the catalogue, so an
-- operator whose runtime does support it can select it per request or promote it here.
('llava:latest', 'LLaVA (latest)', 'vision', NULL, 'ollama', TRUE, NULL, NULL, NULL),
('llava:v1.6', 'LLaVA (v1.6)', 'vision', NULL, 'ollama', FALSE, NULL, NULL, NULL),
('bakllava:latest', 'BakLLaVA (latest)', 'vision', NULL, 'ollama', FALSE, NULL, NULL, NULL),
('llama3.2-vision:latest', 'Llama 3.2 Vision (latest)', 'vision', NULL, 'ollama', FALSE, NULL, NULL, NULL),
-- Themes
('rose', 'Rose Accent', 'theme', NULL, 'ollama', FALSE, NULL, NULL, NULL),
('emerald', 'Emerald Accent', 'theme', NULL, 'ollama', FALSE, NULL, NULL, NULL),
('amber', 'Amber Accent', 'theme', NULL, 'ollama', FALSE, NULL, NULL, NULL),
('zinc', 'Zinc Accent', 'theme', NULL, 'ollama', TRUE, NULL, NULL, NULL),
-- Audio Models
('whisper-base', 'Whisper Base', 'audio', NULL, 'ollama', TRUE, NULL, NULL, NULL),
('whisper-tiny-en', 'Whisper Tiny (en)', 'audio', NULL, 'ollama', FALSE, NULL, NULL, NULL),
-- Code Models
('qwen2.5-coder:7b', 'Qwen 2.5 Coder 7B', 'code', 'q4_K_M', 'ollama', TRUE, NULL, NULL, NULL),
('codellama:7b', 'CodeLlama 7B', 'code', 'q4_K_M', 'ollama', FALSE, NULL, NULL, NULL),
-- MLX Native Models (Apple Silicon Unified Memory)
('argmaxinc/mlx-FLUX.1-schnell-4bit-quantized', 'FLUX.1 Schnell 4-bit (MLX)', 'image', NULL, 'localai-image', FALSE, 4, 0, 'APPLE_SILICON_MLX'),
('ByteDance/AnimateDiff-Lightning', 'AnimateDiff Lightning', 'video', NULL, 'localai-video', FALSE, 8, 12, 'APPLE_SILICON_MLX')
ON CONFLICT (model_name) DO NOTHING;

-- Chat model (required for chat tests — tinyllama is fast & lightweight)
INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default) VALUES
('tinyllama:latest', 'TinyLlama (latest)', 'chat', NULL, 'ollama', TRUE)
ON CONFLICT (model_name) DO NOTHING;

-- The 7B instruct model the voice turn is measured with (ADR-076): good French, ~4.7 GB on an M1
-- Max, Apache-2.0. Never a default — it is chosen (ORAZAKA_OLLAMA_MODEL), like every chat model.
INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default) VALUES
('qwen2.5:7b', 'Qwen 2.5 7B Instruct', 'chat', 'q4_K_M', 'ollama', FALSE)
ON CONFLICT (model_name) DO NOTHING;

-- Unfiltered chat models — open weights without a vendor's refusal layer, never a default: the operator
-- chooses to serve one, and the policy stays the operator's (a pack's scopeGuard and safety, enforced
-- before any model runs; ADR-051, ADR-055). Licences: Apache-2.0 (Qwen), Meta Llama 3 Community Licence
-- (Dolphin), Gemma Terms of Use (Gemma). The 27B needs a 32 GB+ machine. Migration for an existing
-- database: krizaka/orazaka infra/migrations/2026-10-11-uncensored-models.sql.
INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default) VALUES
('huihui_ai/qwen2.5-abliterate:7b', 'Qwen 2.5 7B — unfiltered', 'chat', 'q4_K_M', 'ollama', FALSE),
('dolphin-llama3:8b', 'Dolphin Llama 3 8B — unfiltered', 'chat', 'q4_K_M', 'ollama', FALSE),
('mdq100/Gemma3-Instruct-Abliterated:27b', 'Gemma 3 27B — unfiltered', 'chat', 'q4_K_M', 'ollama', FALSE)
ON CONFLICT (model_name) DO NOTHING;

-- Capabilities (operation-graph registry). The frontend chat composer lists every enabled row
-- here (BootstrapController → /api/v1/features). Two groups:
--   • Generation — synchronous media synthesis on /api/v1/media/generation/* (video/audio/image + chat.speech TTS).
--   • Analysis   — async jobs on /api/v1/media/analyze/* (image/vision + audio transcription), served by
--     MediaAnalysisController; each needs an uploaded asset (${assetId}) and returns {jobId,status}.
-- Analysis rows use registry keys distinct from their generation counterparts, and since ADR-069 the
-- key says which it is: 'orazaka.core.media.audio.analysis' and 'orazaka.core.media.video.analysis'
-- are transcription and video analysis, 'orazaka.core.media.image' and '...video' are generation.
-- handler_key drives async dispatch: each row routes to the JobExecutionStrategy whose
-- handlerKey() equals this value (no @Order, no feature-key substring heuristics on the Java side).
--
-- routing_key / billable_unit (ADR-037 S1) — every value below is a TRANSCRIPTION
-- of what AmqpStepExecutionAdapter.routingKeyFor() returned for that exact key before it was
-- deleted, verified branch by branch. Nothing here changes a capability's effective routing; two
-- results that look wrong are preserved and recorded rather than corrected, because a refactor
-- that also changes behaviour is unreviewable:
--   • orazaka.core.media.audio.analysis (named orazaka.core.media.audio at the time) contains
--     "media", so the chain's third branch caught it before any audio branch could, and it goes to
--     job.media.generate. That placement is right for what the capability actually is — batch
--     transcription — and its handler_key (audio.analyze) said so all along.
--   • orazaka.core.media.speech (named orazaka.core.chat.speech at the time) matched no branch, so
--     TTS falls to job.text.process — synthesis on the text queue, served there by
--     SpeechSynthesisStrategy via handler_key. The routing is deliberate; see the row's own note.
-- billable_unit names the measurement each capability's executor reports, and it must be priced by
-- a current row of credit_pricebook (70-billing.sql) for the same billable_capability —
-- SeedBootstrapIT asserts exactly that, which is what makes this column read rather than decorative.
-- Four of them were NULL until ADR-066, and NULL here was never "the unit belongs to the model": it
-- was four capabilities whose executors measured nothing, so every hold was released (audit #22).
INSERT INTO orazaka_capabilities
    (feature_key, handler_key, routing_key,
     billable_unit, billable_capability, latency_class, input_schema, output_schema, is_enabled) VALUES
('orazaka.core.media.video', 'video.generate', 'job.video.generate', 'OUTPUT_SECOND', 'VIDEO', 'BATCH',
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose", "description": "What must happen on screen."}, "durationSeconds": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5}, "aspect": {"type": "string", "enum": ["9:16", "16:9", "1:1"], "default": "9:16", "description": "The frame: 9:16 is the vertical short (TikTok, Reels, Shorts)."}, "seed": {"type": "string", "description": "Makes the same clip again with the same prompt and model."}, "image": {"type": "string", "format": "asset-id", "description": "An uploaded still to start from (img2vid)."}, "model": {"type": "string"}, "upscale": {"type": "string", "enum": ["none", "1080p", "2160p"], "default": "none", "description": "Optional: SeedVR2 3B takes the finished clip to 1080p or 4K (the run lasts longer)."}}, "required": ["prompt"]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "format": {"type": "string"}, "metrics": {"type": "object"}}, "required": ["url"]}$$::jsonb, true),
-- This row was labelled "Audio Generation" at /api/v1/media/generation/audio, and phase A recorded
-- it as "generation routed to an analysis handler". Both were wrong about WHICH capability it is.
-- There is no /generation/audio endpoint and no audio.generate executor; what actually submits this
-- key is /api/v1/media/analyze/audio, and the CLI binds it to --audio with responseField
-- "analysis". It IS audio analysis, and its handler (audio.analyze) and route (job.media.generate,
-- the job service) were correct all along. Only the label, icon and uri_path lied — corrected here.
-- billable_unit AUDIO_MINUTE (ADR-066): transcription costs what the SOURCE is long, which is what
-- the whisper rows are priced in. It was NULL because nothing measured it — the strategy reported no
-- quantity at all, so every hold was released and the work was served free (audit #22). The
-- transcription provider's `duration` now travels out of ProcessedAudioPayload and the executor
-- reports it. AUDIO still carries two units, one per model: KILOCHAR for synthesis, AUDIO_MINUTE
-- here, which is why the pricebook keeps `unit` out of its key.
('orazaka.core.media.audio.analysis', 'audio.analyze', 'job.media.generate', 'AUDIO_MINUTE', 'AUDIO', 'BATCH',
  $${"type": "object", "properties": {"assetId": {"type": "string", "format": "asset-id"}, "model": {"type": "string"}}, "required": ["assetId"]}$$::jsonb,
  $${"type": "object", "properties": {"analysis": {"type": "string"}}, "required": ["analysis"]}$$::jsonb, true),
-- Image generation takes a seed, a negative prompt and a step count beside the size, and reports
-- how the image was made (model, seed, generation time) so it can be made again (ADR-075).
('orazaka.core.media.image', 'image.generate', 'job.media.generate', 'IMAGE_STEP', 'IMAGE', 'BATCH',
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose"}, "size": {"type": "string", "default": "1024x1024"}, "negativePrompt": {"type": "string"}, "seed": {"type": "string"}, "steps": {"type": "string"}, "model": {"type": "string"}}, "required": ["prompt"]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "format": {"type": "string"}, "model": {"type": "string"}, "seed": {"type": "string"}, "generationMs": {"type": "integer"}}, "required": ["url"]}$$::jsonb, true),
-- Speech synthesis (TTS). Renamed from orazaka.core.chat.speech (ADR-069, #44): the capability is
-- AUDIO and has nothing to do with the chat capability it shared a prefix with, which made the key
-- read as conversational when it is media synthesis. Voice comes from the chosen speech model's
-- options; alloy is the fallback default.
-- routing_key job.text.process is KEPT DELIBERATELY, not by inheritance. Phase A flagged "TTS on
-- the text queue" as suspicious; with routing now data it would be a one-word change to
-- job.media.generate. It should not be made: both keys are drained by the same process (the job
-- service binds job.text.* and job.media.*), so the executor and the result are identical — the
-- only difference is WHICH QUEUE the job waits in. job.media.* carries image and video inference
-- at prefetch 1; a two-second TTS request queued behind a diffusion run would wait for it. The
-- name reads oddly; the placement is right, and latency beats tidiness here.
-- billable_unit KILOCHAR (ADR-066): synthesis costs what the TEXT is long, known before a sample is
-- produced, and the piper/tts-1 rows are priced that way. NULL here meant the executor measured
-- nothing and the hold was released (audit #22); it now reports the characters it sent to the engine.
('orazaka.core.media.speech', 'speech.synthesize', 'job.text.process', 'KILOCHAR', 'AUDIO', 'INTERACTIVE',
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose"}, "text": {"type": "string", "format": "prose"}, "voice": {"type": "string", "default": "alloy"}, "model": {"type": "string"}}, "anyOf": [{"required": ["prompt"]}, {"required": ["text"]}]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "format": {"type": "string"}, "durationMs": {"type": "integer"}}, "required": ["url"]}$$::jsonb, true),
-- Analysis capabilities (async jobs via MediaAnalysisController). Require an uploaded asset.
-- billable_unit NULL, like AUDIO's rows and for the same reason (ADR-041): analysis is priced per
-- MODEL, in KILOTOKEN, because a VLM completion has no denoising steps for IMAGE_STEP to count.
-- Image GENERATION keeps IMAGE_STEP through the (IMAGE, NULL) default row; the two share a
-- capability and not a unit.
-- billable_unit KILOTOKEN (ADR-066, ADR-041): a VLM completion, priced on the tokens the strategy
-- already reported — the column was NULL while the measurement existed, which is how a capability
-- that DOES bill looked identical to three that did not.
-- routing_key job.media.analyze, not job.media.generate (ADR-067). Analysis shared a key with
-- image generation, and one key cannot feed two queues: that is what made the lane impossible while
-- the two capabilities measured p50 4.9 s and p50 67 s on the same consumers. Same grammar, other
-- action. Audio and video analysis stay on job.media.generate because their lane IS batch — their
-- duration follows the file the user supplied, which is what AUDIO_MINUTE already says.
('orazaka.core.media.vision', 'image.analyze', 'job.media.analyze', 'KILOTOKEN', 'IMAGE', 'INTERACTIVE',
  $${"type": "object", "properties": {"assetId": {"type": "string", "format": "asset-id"}, "prompt": {"type": "string", "format": "prose", "default": "Analyze this image"}, "model": {"type": "string"}}, "required": ["assetId"]}$$::jsonb,
  $${"type": "object", "properties": {"analysis": {"type": "string"}}, "required": ["analysis"]}$$::jsonb, true),
-- There is ONE audio-analysis row and its key now says so (ADR-069). It used to be called
-- orazaka.core.media.audio — a key that reads as audio generation for a capability that has always
-- been transcription, whose label, icon and uri_path were corrected in an earlier phase while the
-- key was left lying. A second row under the honest name was rejected then, correctly: two rows
-- would have shown the composer two identical entries. Renaming the one row is what was actually
-- needed, and #44 is the finding that said so.
-- Video analysis. The endpoint (/api/v1/media/analyze/video, called by the web client) and the
-- executor (VideoAnalysisStrategy, handler video.analyze) both existed; only the row was missing,
-- so every request failed at dispatch with "Unknown capability". routing_key is job.media.generate
-- because analysis runs IN the job service — job.video.* reaches the Python worker, which
-- generates rather than analyses. billable_unit AUDIO_MINUTE (ADR-066): an analysis consumes no
-- output seconds — OUTPUT_SECOND would be a fabricated quantity — and both halves of the work,
-- keyframe extraction and transcription, scale with how long the SOURCE is. It is priced under the
-- engine name `orazaka-video-analysis`, the way composition is priced under `orazaka-compose`, so
-- VIDEO's diffusion default cannot be charged for reading a file.
('orazaka.core.media.video.analysis', 'video.analyze', 'job.media.generate', 'AUDIO_MINUTE', 'VIDEO', 'BATCH',
  $${"type": "object", "properties": {"assetId": {"type": "string", "format": "asset-id"}, "model": {"type": "string"}}, "required": ["assetId"]}$$::jsonb,
  $${"type": "object", "properties": {"transcript": {"type": "string"}, "keyframeCount": {"type": "integer"}}, "required": ["transcript"]}$$::jsonb, true),
-- Asynchronous text completion. ChatGenerationStrategy (handler_key text.generate) has always
-- existed in the job service, but no capability row pointed at it — so the async text path was
-- unreachable and any workflow needing generated prose had to abuse a vision capability. Priced
-- under the CHAT pricebook rate, which already exists.
('orazaka.core.chat.completion', 'text.generate', 'job.text.process', 'KILOTOKEN', 'CHAT', 'INTERACTIVE',
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose"}, "text": {"type": "string", "format": "prose"}, "model": {"type": "string"}}, "anyOf": [{"required": ["prompt"]}, {"required": ["text"]}]}$$::jsonb,
  $${"type": "object", "properties": {"content": {"type": "string"}, "metadata": {"type": "object"}}, "required": ["content"]}$$::jsonb, true),
-- Studio media composition (ADR-034 §7.2): photos + clip + voiceover + captions + brand
-- overlay → 9:16 MP4. Or, given `clips` (earlier steps' video outputs) and an `edit` decision list, the cut of a
-- cinematic short (krizaka/orazaka ADR-080, orazaka-worker-media app/cut.py): trims, ramps, transitions, grade, titles. It is a CAPABILITY, not Studio code — the existing Python
-- orazaka-worker-media executes it (app/composer.py, bound to job.compose.*).
-- ENABLED since phase 4, when that worker branch landed.
-- billable_unit OUTPUT_SECOND (ADR-041). The worker has always reported frames and fps
-- (app/composer.py), so the duration needed to price this was on the wire the whole time and only
-- the unit was missing. It is priced at the (VIDEO, 'orazaka-compose') pricebook row rather than
-- VIDEO's default: both are video output, but an ffmpeg concat is not a diffusion run and charging
-- it the generation rate would make composition the most expensive step of a reel by an order of
-- magnitude it does not deserve.
-- billable_capability VIDEO: corrected from the 'CHAT' that phase C transcribed out of the old
-- contains() chain. Composition is ffmpeg/MLX on the accelerator — the most expensive step of a
-- reel — and pricing it as chat understated it by the whole difference between the CHAT and VIDEO
-- pricebook rows. See ADR-038's consequences and the hygiene commit for the measured effect.
('orazaka.studio.media.compose', 'media.compose', 'job.compose.assemble', 'OUTPUT_SECOND', 'VIDEO', 'BATCH',
  $${"type": "object", "properties": {"photos": {"type": "array", "items": {"type": "string", "format": "asset-id"}, "minItems": 1}, "audio": {"type": "string", "format": "asset-id"}, "clips": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 24}, "edit": {"type": "string"}}, "anyOf": [{"required": ["photos"]}, {"required": ["clips", "edit"]}]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "assetId": {"type": "string"}, "format": {"type": "string"}, "metrics": {"type": "object"}}, "required": ["url"]}$$::jsonb, true)
ON CONFLICT (feature_key) DO NOTHING;

-- ── Sound for a video (krizaka/orazaka-packs#25) ──────────────────────────────────────────────────────
-- Every generated clip was silent. `orazaka.studio.media.sound` gives one its sound: a music bed and/or
-- foley and/or a voice-over (the speech asset of the Piper voices), mixed with ducking, levelled to
-- -14 LUFS and muxed as AAC into the mp4; the silent original is kept (`silentUrl`). It is ffmpeg plus a
-- local model on the media worker's host, so it has a queue of its own on that worker (`job.sound.*`, only where MEDIA_SOUND_ENABLED) under its own
-- key and is priced as that worker's other ffmpeg work: OUTPUT_SECOND at the (VIDEO, 'orazaka-compose') row.
-- `video` is the URL a previous step returned or an asset id — only the actor's own files resolve.
-- `foleyModel` is explicit on purpose: the only foley weights this worker can run are non-commercial.
INSERT INTO orazaka_capabilities
    (feature_key, handler_key, routing_key,
     billable_unit, billable_capability, latency_class, input_schema, output_schema, is_enabled) VALUES
('orazaka.studio.media.sound', 'media.sound', 'job.sound.generate', 'OUTPUT_SECOND', 'VIDEO', 'BATCH',
  $${"type": "object", "properties": {"video": {"type": "string", "description": "The clip to give sound to: the url of an earlier step, or one of your files."}, "music": {"type": "string", "format": "prose", "description": "A music bed, described: genre, mood, instruments."}, "foley": {"type": "string", "format": "prose", "description": "The sounds of the picture, described (needs foleyModel: the only foley model is non-commercial)."}, "voice": {"type": "string", "description": "A voice-over: the url or id of a speech file of yours (Speech synthesis)."}, "seed": {"type": "string", "description": "Makes the same sound again with the same prompts and models."}, "musicModel": {"type": "string", "description": "Empty: the commercial default (acestep-v15-xl-turbo)."}, "foleyModel": {"type": "string", "description": "mmaudio-large-44k-v2 (CC-BY-NC: non-commercial). Empty: no foley."}}, "required": ["video"]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "assetId": {"type": "string"}, "silentUrl": {"type": "string"}, "format": {"type": "string"}, "metrics": {"type": "object"}}, "required": ["url"]}$$::jsonb, true)
ON CONFLICT (feature_key) DO NOTHING;

INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default, description) VALUES
('acestep-v15-xl-turbo', 'ACE-Step 1.5 XL turbo (music bed)', 'music', NULL, 'localai-video', TRUE,
 'ACE-Step 1.5 XL turbo (MIT) — a music bed from a prompt, 48 kHz stereo, 8 steps. The commercial default of the sound step; Metal and CUDA.'),
('mmaudio-large-44k-v2', 'MMAudio large 44k v2 (foley) — non-commercial', 'foley', NULL, 'localai-video', FALSE,
 'MMAudio (code MIT, weights CC-BY-NC 4.0) — video-to-audio foley synchronised to the picture. Non-commercial: for evaluation, never a default, asked for by name.')
ON CONFLICT (model_name) DO NOTHING;

UPDATE orazaka_models SET vendor = 'ACE Studio · StepFun', family = 'ACE-Step 1.5', capabilities = 'music', runs_on = 'local', runtime = 'worker-media · Metal, CUDA', open_weights = TRUE, licence = 'MIT', licence_url = 'https://huggingface.co/ACE-Step/acestep-v15-xl-turbo-diffusers', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'acestep-v15-xl-turbo';
UPDATE orazaka_models SET vendor = 'Sony · Hkchengrex (UIUC)', family = 'MMAudio', capabilities = 'video-to-audio', runs_on = 'local', runtime = 'worker-media · Metal, CUDA', open_weights = TRUE, licence = 'CC-BY-NC-4.0', licence_url = 'https://github.com/hkchengrex/MMAudio#licenses', commercial_use = 'no', context_window = NULL, status = 'available' WHERE model_name = 'mmaudio-large-44k-v2';

INSERT INTO orazaka_runtime_config (config_key, config_value, value_type, description) VALUES
('rag.enabled', 'true', 'boolean', 'Master toggle for RAG context retrieval in the pipeline (was orazaka.core.rag.enabled).'),
('rag.top-k', '3', 'int', 'Number of relevant documents RAG retrieves per query (was orazaka.core.rag.top-k).'),
('rate-limit.enabled', 'false', 'boolean', 'Master toggle for per-user rate limiting (was orazaka.rate-limit.enabled; tiers already in rate_limit_tiers).')
ON CONFLICT (config_key) DO NOTHING;

-- Seed default 4-tier validation matrix
INSERT INTO validation_pipeline_configs (step_type, is_enabled, execution_order, configuration_payload) VALUES
    ('STRUCTURAL_A', TRUE,  1, '{"schemaStrict": true}'),
    ('SANDBOX_B',    TRUE,  2, '{"sandboxTimeout": 30}'),
    ('SEMANTIC_C',   TRUE,  3, '{"debateTemperature": 0.0}'),
    ('TDR_D',        FALSE, 4, '{"modelName": "qwen2.5-coder:7b", "assertionTimeout": 60}');

-- EntitlementInterceptor sits at 4 (ADR-033 §6.2): after UserContextResolver has established who
-- the actor is (1), and before the first step that reaches outside this process (McpInterceptor).
-- A gate placed later would refuse the request only after paying for the work it was meant to
-- prevent. It short-circuits to 403 (plan does not include the capability) or 402 (no credits).
INSERT INTO pipeline_interceptor_config (interceptor_key, display_label, execution_order, is_enabled, description) VALUES
('UserContextResolver',    'User Context Resolver',    1, TRUE,  'Resolves user profile, RBAC, and rate-limiting tier'),
('SystemContextInjector',  'System Context Injector',  2, TRUE,  'Injects environment signals, tools, and system variables'),
('RagInterceptor',         'RAG Interceptor',          3, TRUE,  'Vector store retrieval and context injection'),
('EntitlementInterceptor', 'Entitlement Interceptor',  4, TRUE,  'Plan entitlement gate + credit hold (ADR-033) — short-circuits to 403/402'),
('McpInterceptor',         'MCP Interceptor',          5, TRUE,  'External MCP knowledge resolution'),
-- BrandContextInterceptor sits after the actor's context exists and before anything rewrites
-- the prompt (ADR-034 §9.1): a Studio's voice must be in the prompt the refiner sees, not
-- bolted on after it. No-op for any request outside a Studio run.
('BrandContextInterceptor','Brand Context Interceptor',6, TRUE,  'Injects an installed Studio brand kit (ADR-034 §9.1) — no-op outside a Studio run'),
('MemoryInterceptor',      'Memory Interceptor',       7, TRUE,  'Conversation history prepend (FIFO window)'),
('RefinerInterceptor',     'Refiner Interceptor',      8, TRUE,  'Fuzzy query to precise instruction refinement'),
('RouterInterceptor',      'Router Interceptor',       9, TRUE,  'Intent to optimal provider routing (temp: 0.0)'),
('ToolInterceptor',        'Tool Interceptor',         9, TRUE,  'Tool callback attachment (demand-driven)'),
('MediaInterceptor',       'Media Interceptor',        10, TRUE, 'Base64 media extraction and multimodal assembly'),
('UserContextInterceptor', 'User Context Interceptor', 11, TRUE, 'Injects onboarding profile (industry, ai_behavior) as system constraints at inference'),
('SemanticCacheInterceptor','Semantic Cache Interceptor',8, FALSE, 'Answers a repeated question from a cache before any model (ai-engine#40). OFF by default, and a pack must also declare orazaka.pipeline.cache.mode. Runs after the Refiner row (same order, created later), before the Router.'),
-- The verifier of grounded research (ADR-087 slice 2): OFF until the grounding flag of slice 5 turns it on per request.
('CitationVerifierInterceptor','Citation Verifier Interceptor',12, FALSE, 'Checks every claim of a grounded answer against what research read (ADR-087). OFF by default.')
ON CONFLICT (interceptor_key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Model descriptions (surfaced in the playground model selector). Kept as
-- UPDATEs so the INSERT rows above stay readable; one line per model_name.
-- ----------------------------------------------------------------------------
UPDATE orazaka_models SET description = 'Fast, lightweight English neural TTS (Piper). Low fidelity, minimal memory — quick local voice checks.' WHERE model_name = 'piper-en-low';
UPDATE orazaka_models SET description = 'Balanced English neural TTS (Piper, Ryan voice). Natural prosody at modest CPU cost. Non-commercial dataset (CC BY-NC-SA 4.0): for evaluation, not for sale.' WHERE model_name = 'piper-en-medium-ryan';
UPDATE orazaka_models SET description = 'English neural TTS (Piper, LJSpeech voice, public domain). Clear, neutral prosody at modest CPU cost — commercially usable. Local speech default.' WHERE model_name = 'piper-en-medium-ljspeech';
UPDATE orazaka_models SET description = 'French neural TTS (Piper). Natural French speech, medium quality, CPU-friendly.' WHERE model_name = 'piper-fr-medium';
UPDATE orazaka_models SET description = 'Qwen 2.5 7B Instruct (Apache-2.0) — fluent French and English, the model the voice turn is measured with: first spoken sentence in about two seconds on an M1 Max.' WHERE model_name = 'qwen2.5:7b';
UPDATE orazaka_models SET description = 'French neural TTS (Piper, Siwis voice, female, CC-BY 4.0). Clear, natural French — the fr-FR voice of the voice turn.' WHERE model_name = 'piper-fr-siwis-medium';
UPDATE orazaka_models SET description = 'French neural TTS (Piper, Tom voice, male, AGPL-3.0 weights). 44.1 kHz, warm timbre — the second fr-FR voice of the voice turn.' WHERE model_name = 'piper-fr-tom-medium';
-- Each voice's language, declared, and the voice a locale gets by default (ADR-076).
UPDATE orazaka_models SET locale = 'en' WHERE model_name IN ('piper-en-low', 'piper-en-medium-ljspeech', 'piper-en-medium-ryan');
UPDATE orazaka_models SET locale = 'fr-FR' WHERE model_name IN ('piper-fr-medium', 'piper-fr-siwis-medium', 'piper-fr-tom-medium');
UPDATE orazaka_models SET locale_default = TRUE WHERE model_name IN ('piper-en-medium-ljspeech', 'piper-fr-siwis-medium');
UPDATE orazaka_models SET description = 'OpenAI-compatible TTS with six selectable voices (Alloy, Echo, Fable, Onyx, Nova, Shimmer), served locally via LocalAI.' WHERE model_name = 'tts-1';
UPDATE orazaka_models SET description = 'FLUX.2 [klein] 4B (Apache-2.0) — editorial photographs, illustrations and miniatures in 4 steps, 1024 px in about 80 s on an M1 Max. Local image default.' WHERE model_name = 'flux-2-klein-4b';
UPDATE orazaka_models SET description = 'Z-Image Turbo (Apache-2.0) — the most detailed photographs, about three times slower than FLUX.2 [klein]. Renders lettering readily: keep words out of the prompt.' WHERE model_name = 'z-image-turbo';
UPDATE orazaka_models SET description = 'Stable Diffusion 1.5 (CreativeML OpenRAIL-M) — the former default: 512 px, short prompts. For small machines.' WHERE model_name = 'v1-5-pruned-emaonly';
UPDATE orazaka_models SET description = 'FLUX.1 Schnell, 4-bit quantized for Apple MLX. Fast, high-quality images natively on Apple Silicon.' WHERE model_name = 'argmaxinc/mlx-FLUX.1-schnell-4bit-quantized';
UPDATE orazaka_models SET description = 'Stable Video Diffusion XT — highest-quality image-to-video. Heavy: needs >64GB unified memory or CUDA. Not recommended on a 64GB Mac.' WHERE model_name = 'stable-video-diffusion-img2vid-xt';
UPDATE orazaka_models SET description = 'AnimateDiff Lightning on Apple Metal (MPS). Fast 8-step text-to-video that fits a 64GB Mac. Local video default.' WHERE model_name = 'animatediff-lightning-mps';
UPDATE orazaka_models SET description = 'Apple CoreML video pipeline. Native Apple Silicon acceleration for short clips.' WHERE model_name = 'apple-coreml-video-pipeline';
UPDATE orazaka_models SET description = 'AnimateDiff Lightning on Apple MLX. Native Apple-Silicon 8-step video at ~12fps.' WHERE model_name = 'mlx-animatediff-lightning';
UPDATE orazaka_models SET description = 'Stable Diffusion video on Apple MLX. Higher quality (25 steps, ~14fps); heavier than AnimateDiff.' WHERE model_name = 'mlx-stable-diffusion-video';
UPDATE orazaka_models SET description = 'SVD XT in PyTorch MPS float32. Highest fidelity but ~96GB memory — will fail on a 64GB Mac.' WHERE model_name = 'stable-video-diffusion-img2vid-xt-mps-fp32';
UPDATE orazaka_models SET description = 'ByteDance AnimateDiff Lightning weights (MLX). Fast 8-step text-to-video at ~12fps.' WHERE model_name = 'ByteDance/AnimateDiff-Lightning';
UPDATE orazaka_models SET description = 'Wan 2.2 TI2V 5B (Apache-2.0) — vertical shorts at 704x1280, 5 s at 24 fps, on the GPU host (48 GB). The 9:16 model of the demos.' WHERE model_name = 'wan2.2-ti2v-5b';
UPDATE orazaka_models SET description = 'Wan 2.1 T2V 1.3B (Apache-2.0) — vertical clips at 480x832, 5 s at 16 fps, on the small GPU host (20 GB). Fast drafts.' WHERE model_name = 'wan2.1-t2v-1.3b';
UPDATE orazaka_models SET description = 'Wan 2.2 A14B (Apache-2.0) on DigitalOcean serverless inference, routed by Orazaka — text-to-video, 832x480 in 16:9, 5 s at 16 fps, $0.60 a clip, the seed makes the same shot again. The cinematic model of the demos.' WHERE model_name = 'wan2-2-t2v-a14b';
UPDATE orazaka_models SET description = 'Stable Diffusion 3.5 Large (Stability AI Community Licence) on DigitalOcean serverless inference, routed by Orazaka — $0.08 an image, 1024x1024, the seed is not honoured by the provider.' WHERE model_name = 'stable-diffusion-3.5-large';
UPDATE orazaka_models SET description = 'LLaVA multimodal model — describe and answer questions about images.' WHERE model_name = 'llava:latest';
UPDATE orazaka_models SET description = 'LLaVA v1.6 — improved image understanding and OCR over base LLaVA.' WHERE model_name = 'llava:v1.6';
UPDATE orazaka_models SET description = 'BakLLaVA (Mistral-based) vision model. Compact image understanding.' WHERE model_name = 'bakllava:latest';
UPDATE orazaka_models SET description = 'Llama 3.2 Vision — strong general image understanding. Local vision default.' WHERE model_name = 'llama3.2-vision:latest';
UPDATE orazaka_models SET description = 'Whisper Base speech-to-text. Balanced accuracy and speed for transcription. Local audio default.' WHERE model_name = 'whisper-base';
UPDATE orazaka_models SET description = 'Whisper Tiny (English) STT. Fastest, smallest footprint — quick English transcriptions.' WHERE model_name = 'whisper-tiny-en';
UPDATE orazaka_models SET description = 'Qwen 2.5 Coder 7B — code generation and completion (q4_K_M). Local code default.' WHERE model_name = 'qwen2.5-coder:7b';
UPDATE orazaka_models SET description = 'Code Llama 7B — code generation and explanation (q4_K_M).' WHERE model_name = 'codellama:7b';
UPDATE orazaka_models SET description = 'TinyLlama — tiny, fast chat model for local testing. Limited reasoning.' WHERE model_name = 'tinyllama:latest';
UPDATE orazaka_models SET description = 'Qwen 2.5 7B Instruct with the refusal direction removed (abliterated), Apache-2.0. Answers legitimate professional questions — security, medicine, law, fiction — without a vendor''s refusals; your packs set the rules. French and English.' WHERE model_name = 'huihui_ai/qwen2.5-abliterate:7b';
UPDATE orazaka_models SET description = 'Dolphin 2.9 on Llama 3 8B (Meta Llama 3 Community Licence) — fine-tuned without a refusal layer. Frank, compliant answers; the operator''s packs set the rules.' WHERE model_name = 'dolphin-llama3:8b';
UPDATE orazaka_models SET description = 'Gemma 3 27B Instruct with the refusal direction removed (abliterated), Gemma Terms of Use. The most accurate unfiltered model of the catalogue — the one behind the krizaka.com demos. ~17 GB: a 32 GB+ machine.' WHERE model_name = 'mdq100/Gemma3-Instruct-Abliterated:27b';

-- Registry metadata (vendor, family, capabilities, where it runs, licence from the model's own card, context window).
-- A model without it stays 'internal' — in the catalogue, never published. Provider-hosted models (runs_on = 'provider')
-- are seeded by the deployment that has the provider (krizaka/orazaka infra/deploy/dev-model-registry.sql).
UPDATE orazaka_models SET vendor = 'Alibaba Cloud', family = 'Qwen 2.5', capabilities = 'chat', runs_on = 'local', runtime = 'Ollama', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Qwen/Qwen2.5-7B-Instruct/blob/main/LICENSE', commercial_use = 'yes', context_window = 32768, status = 'available' WHERE model_name = 'qwen2.5:7b';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'huihui_ai/qwen2.5-abliterate:7b';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'dolphin-llama3:8b';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'mdq100/Gemma3-Instruct-Abliterated:27b';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'tinyllama:latest';
UPDATE orazaka_models SET vendor = 'Alibaba Cloud', family = 'Qwen 2.5 Coder', capabilities = 'code', runs_on = 'local', runtime = 'Ollama', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct/blob/main/LICENSE', commercial_use = 'yes', context_window = 32768, status = 'available' WHERE model_name = 'qwen2.5-coder:7b';
UPDATE orazaka_models SET vendor = 'Meta', family = 'Code Llama', capabilities = 'code', runs_on = 'local', runtime = 'Ollama', open_weights = TRUE, licence = 'Llama 2 Community Licence', licence_url = 'https://ai.meta.com/llama/license/', commercial_use = 'conditions', context_window = 16384, status = 'available' WHERE model_name = 'codellama:7b';
UPDATE orazaka_models SET vendor = 'LLaVA team', family = 'LLaVA 1.6', capabilities = 'vision', runs_on = 'local', runtime = 'Ollama', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://ollama.com/library/llava', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'llava:latest';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'llava:v1.6';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'bakllava:latest';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'llama3.2-vision:latest';
UPDATE orazaka_models SET vendor = 'OpenAI', family = 'Whisper', capabilities = 'speech-to-text', runs_on = 'local', runtime = 'LocalAI · whisper.cpp', open_weights = TRUE, licence = 'MIT', licence_url = 'https://github.com/openai/whisper/blob/main/LICENSE', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'whisper-base';
UPDATE orazaka_models SET vendor = 'OpenAI', family = 'Whisper', capabilities = 'speech-to-text', runs_on = 'local', runtime = 'LocalAI · whisper.cpp', open_weights = TRUE, licence = 'MIT', licence_url = 'https://github.com/openai/whisper/blob/main/LICENSE', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'whisper-tiny-en';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (en-US, Lessac)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'Lessac Blizzard 2013 data licence', licence_url = 'https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html', commercial_use = 'no', context_window = NULL, status = 'available' WHERE model_name = 'piper-en-low';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (en-US, LJSpeech)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'Public domain', licence_url = 'https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ljspeech/medium/MODEL_CARD', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'piper-en-medium-ljspeech';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (en-US, Ryan)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'CC BY-NC-SA 4.0', licence_url = 'https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/medium/MODEL_CARD', commercial_use = 'no', context_window = NULL, status = 'available' WHERE model_name = 'piper-en-medium-ryan';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (fr-FR, UPMC)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'CC BY-SA 4.0', licence_url = 'https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/upmc/medium/MODEL_CARD', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'piper-fr-medium';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (fr-FR, Siwis)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'CC BY 4.0', licence_url = 'https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/medium/MODEL_CARD', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'piper-fr-siwis-medium';
UPDATE orazaka_models SET vendor = 'Rhasspy', family = 'Piper (fr-FR, Tom)', capabilities = 'text-to-speech', runs_on = 'local', runtime = 'LocalAI · Piper', open_weights = TRUE, licence = 'AGPL-3.0', licence_url = 'https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/tom/medium/MODEL_CARD', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'piper-fr-tom-medium';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'tts-1';
UPDATE orazaka_models SET vendor = 'Black Forest Labs', family = 'FLUX.2 klein', capabilities = 'image', runs_on = 'local', runtime = 'stable-diffusion.cpp · Metal, CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/black-forest-labs/FLUX.2-klein-4B/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'flux-2-klein-4b';
UPDATE orazaka_models SET vendor = 'Tongyi Lab (Alibaba)', family = 'Z-Image', capabilities = 'image', runs_on = 'local', runtime = 'stable-diffusion.cpp · Metal, CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Tongyi-MAI/Z-Image-Turbo/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'z-image-turbo';
UPDATE orazaka_models SET vendor = 'Stability AI', family = 'Stable Diffusion 1.5', capabilities = 'image', runs_on = 'local', runtime = 'stable-diffusion.cpp · Metal, CUDA', open_weights = TRUE, licence = 'CreativeML OpenRAIL-M', licence_url = 'https://huggingface.co/spaces/CompVis/stable-diffusion-license', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'v1-5-pruned-emaonly';
UPDATE orazaka_models SET vendor = 'Black Forest Labs', family = 'FLUX.1 schnell', capabilities = 'image', runs_on = 'local', runtime = 'MLX · Apple Silicon', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/argmaxinc/mlx-FLUX.1-schnell-4bit-quantized/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'argmaxinc/mlx-FLUX.1-schnell-4bit-quantized';
UPDATE orazaka_models SET vendor = 'ByteDance', family = 'AnimateDiff-Lightning', capabilities = 'video', runs_on = 'local', runtime = 'PyTorch MPS · Apple Metal', open_weights = TRUE, licence = 'CreativeML OpenRAIL-M', licence_url = 'https://huggingface.co/ByteDance/AnimateDiff-Lightning/blob/main/README.md', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'animatediff-lightning-mps';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'ByteDance/AnimateDiff-Lightning';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'mlx-animatediff-lightning';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'mlx-stable-diffusion-video';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'apple-coreml-video-pipeline';
UPDATE orazaka_models SET vendor = 'Stability AI', family = 'Stable Video Diffusion', capabilities = 'video', runs_on = 'local', runtime = 'CUDA, more than 64 GB of memory', open_weights = TRUE, licence = 'Stability AI Community Licence', licence_url = 'https://huggingface.co/stabilityai/stable-video-diffusion-img2vid-xt/blob/main/LICENSE.md', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'stable-video-diffusion-img2vid-xt';
UPDATE orazaka_models SET status = 'internal' WHERE model_name = 'stable-video-diffusion-img2vid-xt-mps-fp32';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.2', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.2-TI2V-5B-Diffusers/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'wan2.2-ti2v-5b';
UPDATE orazaka_models SET vendor = 'Stability AI', family = 'Stable Diffusion 3.5', capabilities = 'image', runs_on = 'provider', runtime = 'DigitalOcean serverless inference', open_weights = TRUE, licence = 'Stability AI Community Licence', licence_url = 'https://stability.ai/license', commercial_use = 'conditions', context_window = NULL, status = 'available' WHERE model_name = 'stable-diffusion-3.5-large';
UPDATE orazaka_models SET description = 'SeedVR2 3B (Apache-2.0, ByteDance Seed) — optional video super-resolution after the clip: 720p to 1080p in about 107 s for a 5 s clip, to 4K in about 280 s, on an H100 80 GB ($0.12 and $0.31 of GPU time at $3.99 an hour — measured runs, docs/_generated/MEASURED_RUNS.md). Look at the frames: it adds detail without inventing content.', vendor = 'ByteDance Seed', family = 'SeedVR2', capabilities = 'video-upscale', runs_on = 'local', runtime = 'worker-media · CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/ByteDance-Seed/SeedVR2-3B', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'seedvr2-3b';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.1', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.1-T2V-1.3B-Diffusers/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'wan2.1-t2v-1.3b';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.2', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.2-T2V-A14B-Diffusers/blob/main/README.md', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'wan2.2-t2v-a14b';
UPDATE orazaka_models SET vendor = 'Lightricks', family = 'LTX-Video', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA (80 GB)', open_weights = TRUE, licence = 'LTX-Video Open Weights License 0.X', licence_url = 'https://huggingface.co/Lightricks/LTX-Video-0.9.8-13B-distilled/blob/main/LTX-Video-Open-Weights-License-0.X.txt', commercial_use = 'yes', context_window = NULL, status = 'available', description = 'LTX-Video 0.9.8 13B distilled (Lightricks, Open Weights License, commercial use) on our own 80 GB GPU worker — image or text to video, 1216x704 or 704x1216, 5 s at 24 fps, 8 steps: about 14 s a clip and 39 GB of VRAM (measured). The fast model of the bake-off: landscape and iteration.' WHERE model_name = 'ltx-video-0.9.8-13b-distilled';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.2', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA (80 GB)', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.2-I2V-A14B-Diffusers', commercial_use = 'yes', context_window = NULL, status = 'available', description = 'Wan 2.2 I2V A14B (Apache-2.0) at its full 40-step schedule on our own 80 GB GPU worker — image-to-video at 1280x720 or 720x1280, 5 s at 16 fps, from a starting image only, about 18 minutes and 35 GB of VRAM a clip (measured). The bake-off winner for people and animals in 9:16.' WHERE model_name = 'wan2.2-i2v-a14b-hq';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.2', capabilities = 'video', runs_on = 'local', runtime = 'worker-media · CUDA (80 GB)', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.2-I2V-A14B-Diffusers', commercial_use = 'yes', context_window = NULL, status = 'available', description = 'Wan 2.2 I2V A14B (Apache-2.0) with lightx2v''s 4-step distillation LoRAs (Apache-2.0), on our own 80 GB GPU worker — image-to-video at 1280x720 or 720x1280, 5 s at 16 fps, from a starting image only. The shots of a cinematic short (ADR-080).' WHERE model_name = 'wan2.2-i2v-a14b';
UPDATE orazaka_models SET vendor = 'Alibaba (Wan-AI)', family = 'Wan 2.2', capabilities = 'video', runs_on = 'provider', runtime = 'DigitalOcean serverless inference', open_weights = TRUE, licence = 'Apache-2.0', licence_url = 'https://huggingface.co/Wan-AI/Wan2.2-T2V-A14B', commercial_use = 'yes', context_window = NULL, status = 'available' WHERE model_name = 'wan2-2-t2v-a14b';


-- ── MCP registry + tool cache/config (router-hosted MCP execution; Phase 4 kept the
--    RAG *sources* out — those moved to the knowledge service's own database) ──────────
CREATE TABLE user_mcp_servers (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    label VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    -- Sealed (v1.<key>.<wrapped>.<nonce>.<ciphertext>) for its owner, never in clear (orazaka-ai-engine#58, ADR-054).
    auth_token TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_user_mcp_servers_user ON user_mcp_servers(user_id);

CREATE TABLE platform_mcp_servers (
    id SERIAL PRIMARY KEY,
    label VARCHAR(255) NOT NULL,
    transport_type VARCHAR(50) NOT NULL,
    url VARCHAR(1000),
    command VARCHAR(1000),
    args VARCHAR(2000),
    -- Sealed for the platform scope, never in clear (orazaka-ai-engine#58, ADR-054).
    auth_token TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_tool_configs (
    id SERIAL PRIMARY KEY,
    tool_id VARCHAR(255) NOT NULL UNIQUE,
    cache_enabled BOOLEAN DEFAULT TRUE,
    cache_ttl_seconds INT DEFAULT 3600,
    rag_enabled BOOLEAN DEFAULT TRUE,
    chunker_type VARCHAR(100) DEFAULT 'MARKDOWN_CHUNKERS',
    source_table VARCHAR(255) DEFAULT 'orazaka_tools_rag_source',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orazaka_tools_cache (
    tool_id VARCHAR(255) NOT NULL,
    cache_key TEXT NOT NULL,
    cache_value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tool_id, cache_key)
);

INSERT INTO platform_tool_configs (tool_id, cache_enabled, cache_ttl_seconds, rag_enabled, chunker_type, source_table) VALUES
-- searchKnowledge reads the knowledge corpus, not the web; it was named searchWeb (orazaka-ai-engine#59, ADR-087).
('searchKnowledge', TRUE, 3600, TRUE, 'MARKDOWN_CHUNKERS', 'orazaka_tools_rag_source')
ON CONFLICT (tool_id) DO NOTHING;

-- What the Studio form's model picker shows (command-to-file seconds and price from content/docs/measured-runs.md,
-- measured 2026-10-15). Models never measured stay NULL: the picker then says nothing rather than guessing.
UPDATE orazaka_models SET typical_seconds = 62 WHERE model_name = 'flux-2-klein-4b';
UPDATE orazaka_models SET typical_seconds = 58 WHERE model_name = 'v1-5-pruned-emaonly';
UPDATE orazaka_models SET typical_seconds = 157 WHERE model_name = 'z-image-turbo';
UPDATE orazaka_models SET typical_seconds = 8, price_cents = 8 WHERE model_name = 'stable-diffusion-3.5-large';
UPDATE orazaka_models SET typical_seconds = 131, price_cents = 60 WHERE model_name = 'wan2-2-t2v-a14b';

-- ============================================================================
-- GROUNDED RESEARCH (ADR-087, orazaka-job-service#62) — the engine reads a source and cites it.
-- Sources are rows the administrator manages; what was read is stored once (URL + content hash); every search or
-- fetch leaves an audit row, never the query or the path in clear (ADR-064). Off until research.enabled is true.
-- ============================================================================
CREATE TABLE IF NOT EXISTS research_source (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key           VARCHAR(80)  NOT NULL UNIQUE,
    kind                 VARCHAR(20)  NOT NULL CHECK (kind IN ('SEARCH','FETCH','OFFICIAL','CODE_HOST','MCP')),
    -- A vendor is a value, never a type: searxng, brave, http, justice-laws, canlii, legisquebec, registraire, github, mcp.
    provider             VARCHAR(40)  NOT NULL,
    scope                VARCHAR(10)  NOT NULL DEFAULT 'GLOBAL' CHECK (scope IN ('GLOBAL','PACK','STUDIO','ACTOR')),
    scope_ref            VARCHAR(255),
    base_url             VARCHAR(1000),
    -- host[/path] globs (* within a segment run, ** across); empty = nothing is allowed (closed by default).
    allow_patterns       TEXT[]       NOT NULL DEFAULT '{}',
    deny_patterns        TEXT[]       NOT NULL DEFAULT '{}',
    allow_http           BOOLEAN      NOT NULL DEFAULT FALSE,
    freshness_seconds    INT          NOT NULL DEFAULT 86400 CHECK (freshness_seconds >= 0),
    rate_per_minute      INT          NOT NULL DEFAULT 30 CHECK (rate_per_minute > 0),
    allows_personal_data BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The id of an encrypted credential (ADR-054), never the secret.
    credential_ref       VARCHAR(255),
    verify_mode          VARCHAR(10)  CHECK (verify_mode IN ('MARK','STRICT')),
    enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by           VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_research_source_scope_ref CHECK ((scope = 'GLOBAL') = (scope_ref IS NULL))
);

CREATE TABLE IF NOT EXISTS research_document (
    -- The source that authorised every hop is part of the key: a hit is served only through that source, under its
    -- current policy and freshness, so a credentialed or actor-scoped source never answers another (ADR-087 §5).
    source_id      UUID        NOT NULL REFERENCES research_source(id) ON DELETE CASCADE,
    -- The URL as requested (the lookup key) and the URL after redirects (what a citation names).
    url            TEXT        NOT NULL,
    content_hash   CHAR(64)    NOT NULL,
    final_url      TEXT        NOT NULL,
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    http_status    SMALLINT    NOT NULL,
    content_type   VARCHAR(120),
    bytes          INT         NOT NULL,
    etag           TEXT,
    last_modified  TEXT,
    title          TEXT,
    text           TEXT        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, url, content_hash)
);
CREATE INDEX IF NOT EXISTS idx_research_document_fresh ON research_document (source_id, url, fetched_at DESC);

CREATE TABLE IF NOT EXISTS research_fetch (
    id             BIGSERIAL   PRIMARY KEY,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id       VARCHAR(255),
    run_id         UUID,
    chat_id        VARCHAR(255),
    source_id      UUID        REFERENCES research_source(id) ON DELETE SET NULL,
    operation      VARCHAR(10) NOT NULL CHECK (operation IN ('SEARCH','FETCH','LOOKUP','ROBOTS')),
    host           VARCHAR(255),
    path_hash      CHAR(64),
    http_status    SMALLINT,
    bytes          INT,
    duration_ms    INT,
    cache_hit      BOOLEAN     NOT NULL DEFAULT FALSE,
    refused_reason VARCHAR(20) CHECK (refused_reason IN
        ('SSRF','SCHEME','ROBOTS','ALLOWLIST','RATE','SIZE','TIMEOUT','CONSENT','CONTENT_TYPE','REDIRECTS','DISABLED','HTTP_ERROR'))
);
CREATE INDEX IF NOT EXISTS idx_research_fetch_at ON research_fetch (at DESC);
CREATE INDEX IF NOT EXISTS idx_research_fetch_source ON research_fetch (source_id, at DESC);

CREATE TABLE IF NOT EXISTS research_consent (
    actor_id    VARCHAR(255) NOT NULL,
    source_id   UUID         NOT NULL REFERENCES research_source(id) ON DELETE CASCADE,
    purpose     TEXT         NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    PRIMARY KEY (actor_id, source_id, granted_at)
);

-- The research settings. Off by default; production search stays off until SearXNG is validated (ADR-087 review).
-- First values, not measurements: slice 5 measures latency and cost before any default changes.
INSERT INTO orazaka_runtime_config (config_key, config_value, value_type, description) VALUES
('research.enabled', 'false', 'boolean', 'Master switch of grounded research (ADR-087).'),
('research.max-searches', '3', 'int', 'Searches per answer, declared into the run budget of ADR-088.'),
('research.max-fetches', '8', 'int', 'Page fetches per answer, declared into the run budget of ADR-088.'),
('research.max-calls', '16', 'int', 'Every billable research operation of one answer (searches, fetches, lookups, the checks of the verifier), declared into the run budget of ADR-088.'),
('research.fetch.timeout-ms', '10000', 'int', 'Connect + read time limit of one fetch.'),
('research.fetch.max-bytes', '2097152', 'int', 'Largest body read before extraction (2 MB).'),
('research.fetch.max-redirects', '3', 'int', 'Redirect hops followed, each one checked again.'),
('research.fetch.per-host-per-minute', '30', 'int', 'Fetches per host and minute, per instance.'),
('research.fetch.content-types', 'text/html,application/xhtml+xml,text/plain,application/xml', 'string', 'Content types read; PDF text comes in a later slice.'),
('research.quote.max-chars', '300', 'int', 'Longest quoted passage in a citation.'),
('research.verify.mode', 'MARK', 'string', 'MARK keeps an unverified claim as TO_VALIDATE; STRICT drops it.'),
('research.cache.default-freshness-seconds', '86400', 'int', 'Cache lifetime when a source sets none.'),
('research.user-agent', 'OrazakaResearch/0.1 (+https://www.orazaka.com)', 'string', 'The honest user agent of every research request.')
ON CONFLICT (config_key) DO NOTHING;

-- The first sources, all disabled: the administrator turns one on. LégisQuébec and the Registraire stay off until
-- their access conditions are validated (krizaka/orazaka#393).
INSERT INTO research_source (source_key, kind, provider, base_url, allow_patterns, freshness_seconds, enabled) VALUES
('justice-laws', 'OFFICIAL', 'justice-laws', 'https://laws-lois.justice.gc.ca', '{laws-lois.justice.gc.ca/**}', 604800, FALSE),
('canlii', 'OFFICIAL', 'canlii', 'https://api.canlii.org', '{api.canlii.org/**,www.canlii.org/**}', 604800, FALSE),
('legisquebec', 'OFFICIAL', 'legisquebec', 'https://www.legisquebec.gouv.qc.ca', '{www.legisquebec.gouv.qc.ca/**}', 604800, FALSE),
('registraire', 'OFFICIAL', 'registraire', 'https://www.registreentreprises.gouv.qc.ca', '{www.registreentreprises.gouv.qc.ca/**}', 604800, FALSE),
('github-krizaka', 'CODE_HOST', 'github', 'https://api.github.com', '{api.github.com/repos/krizaka/**,github.com/krizaka/**}', 3600, FALSE)
ON CONFLICT (source_key) DO NOTHING;

-- ============================================================================
-- LAW CORPUS (ADR-089 §2 and §10, orazaka-job-service#67) — the dated, hashed text of the law the engine derives its
-- checks from. Beside the research tables, in this service's database (connection budget, ADR-083). Off until
-- law.corpus.enabled is true; the sources law-corpus and donnees-quebec are seeded disabled. The same block is the
-- dev-deploy migration `law-corpus` (krizaka/orazaka): additive and idempotent, the two must stay in step.
-- ============================================================================

-- Settings carry their default and bounds, like studio_runtime_config. A row without a default is as before; a row
-- with one is checked by the database itself: an out-of-range int, a list longer than max_items or a boolean that is
-- not true/false is refused whoever writes it (the admin API, a script, a console).
ALTER TABLE orazaka_runtime_config
    ADD COLUMN IF NOT EXISTS default_value VARCHAR(255),
    ADD COLUMN IF NOT EXISTS min_value     BIGINT,
    ADD COLUMN IF NOT EXISTS max_value     BIGINT,
    ADD COLUMN IF NOT EXISTS max_items     INTEGER;

CREATE OR REPLACE FUNCTION orazaka_config_value_ok(vtype TEXT, val TEXT, lo BIGINT, hi BIGINT, items INTEGER)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE vtype
        WHEN 'int' THEN CASE WHEN val ~ '^-?[0-9]{1,18}$'
            THEN val::bigint BETWEEN COALESCE(lo, -9223372036854775807) AND COALESCE(hi, 9223372036854775807)
            ELSE FALSE END
        WHEN 'boolean' THEN val IN ('true', 'false')
        WHEN 'list' THEN items IS NULL OR cardinality(string_to_array(val, ',')) <= items
        ELSE TRUE
    END
$fn$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orazaka_runtime_config_bounds') THEN
        ALTER TABLE orazaka_runtime_config ADD CONSTRAINT ck_orazaka_runtime_config_bounds CHECK (
            (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
            AND (max_items IS NULL OR max_items >= 0));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orazaka_runtime_config_value') THEN
        ALTER TABLE orazaka_runtime_config ADD CONSTRAINT ck_orazaka_runtime_config_value CHECK (
            default_value IS NULL
            OR (orazaka_config_value_ok(value_type, config_value, min_value, max_value, max_items)
                AND orazaka_config_value_ok(value_type, default_value, min_value, max_value, max_items)));
    END IF;
END
$do$;

-- One row per act or regulation.
CREATE TABLE IF NOT EXISTS law_instrument (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction          VARCHAR(10)  NOT NULL CHECK (jurisdiction IN ('CA','QC','FR','BE-WAL')),
    -- CCQ-1991, C-11, T-0.1, T-15.01-r3, E-15, SOR-91-45.
    code                  VARCHAR(60)  NOT NULL,
    title_fr              TEXT,
    title_en              TEXT,
    -- The address every citation shows, with {ref} for the article (e.g. ...?code=se:{ref}).
    official_url_template TEXT         NOT NULL,
    source_key            VARCHAR(80)  NOT NULL REFERENCES research_source(source_key) ON UPDATE CASCADE,
    licence               TEXT,
    access_note           TEXT,
    UNIQUE (jurisdiction, code)
);

-- Every import is traceable, a manual one included: who, which file, its hash.
CREATE TABLE IF NOT EXISTS law_import (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key          VARCHAR(80)  NOT NULL REFERENCES research_source(source_key) ON UPDATE CASCADE,
    mode                VARCHAR(10)  NOT NULL CHECK (mode IN ('AUTOMATED','MANUAL')),
    dataset_url         TEXT,
    dataset_etag        TEXT,
    dataset_sha256      CHAR(64)     CHECK (dataset_sha256 ~ '^[0-9a-f]{64}$'),
    consolidated_to     DATE,
    imported_by         VARCHAR(255),
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    articles_new        INT          NOT NULL DEFAULT 0 CHECK (articles_new >= 0),
    articles_changed    INT          NOT NULL DEFAULT 0 CHECK (articles_changed >= 0),
    articles_unchanged  INT          NOT NULL DEFAULT 0 CHECK (articles_unchanged >= 0),
    -- ADR-089 names the column, not its values: RUNNING until finished_at is set, then SUCCEEDED or FAILED.
    status              VARCHAR(10)  NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_law_import_manual_traceable
        CHECK (mode = 'AUTOMATED' OR (imported_by IS NOT NULL AND dataset_sha256 IS NOT NULL))
);

-- The key is the period, not the text: a reverted amendment brings back an earlier text, so its hash is already a row
-- and the new period must still be a new row. A changed text is a new row; the previous row gets in_force_to. The hash is the SHA-256 of the article's text (label
-- and subsections; the historical note is stored, not hashed), NFC, whitespace collapsed. asOf selects the version
-- with in_force_from <= asOf < in_force_to.
CREATE TABLE IF NOT EXISTS law_article_version (
    instrument_id  UUID         NOT NULL REFERENCES law_instrument(id),
    ref            VARCHAR(40)  NOT NULL,
    language       VARCHAR(2)   NOT NULL CHECK (language IN ('fr','en')),
    in_force_from  DATE         NOT NULL,
    in_force_to    DATE,
    heading_path   TEXT,
    text           TEXT         NOT NULL,
    content_hash   CHAR(64)     NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    history_note   TEXT,
    import_id      UUID         NOT NULL REFERENCES law_import(id),
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, ref, language, in_force_from),
    CONSTRAINT ck_law_article_version_span CHECK (in_force_to IS NULL OR in_force_to >= in_force_from)
);
-- At most one open version of an article: the importer closes the old row (in_force_to) before it writes the new one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_law_article_version_open
    ON law_article_version (instrument_id, ref, language) WHERE in_force_to IS NULL;
CREATE INDEX IF NOT EXISTS idx_law_article_version_as_of
    ON law_article_version (instrument_id, ref, language, in_force_from DESC);
CREATE INDEX IF NOT EXISTS idx_law_article_version_hash
    ON law_article_version (instrument_id, ref, language, content_hash);
CREATE INDEX IF NOT EXISTS idx_law_article_version_import ON law_article_version (import_id);

-- The cache of stage 2: the articles kept for a kind, with the verified claims, redone when one of them changes.
CREATE TABLE IF NOT EXISTS law_retrieval (
    jurisdiction  VARCHAR(10) NOT NULL,
    kind_id       VARCHAR(80) NOT NULL,
    -- The language of the claims: a French retrieval is never served for an English request.
    language      VARCHAR(2)  NOT NULL CHECK (language IN ('fr','en')),
    -- The in_force_from of the newest article it used.
    as_of_bucket  DATE        NOT NULL,
    -- [{instrument, ref, language, contentHash}]
    articles      JSONB       NOT NULL DEFAULT '[]',
    -- ADR-087 claims.
    claims        JSONB       NOT NULL DEFAULT '[]',
    run_id        UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    stale         BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (jurisdiction, kind_id, language, as_of_bucket)
);

-- A proposal never leaves this table except through promotion (stage 5). An edit is a new proposal_revision.
CREATE TABLE IF NOT EXISTS rule_proposal (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction        VARCHAR(10)  NOT NULL,
    kind_id             VARCHAR(80)  NOT NULL,
    rule_id             VARCHAR(120) NOT NULL,
    proposal_revision   INT          NOT NULL DEFAULT 1 CHECK (proposal_revision >= 1),
    rule                JSONB        NOT NULL,
    law                 JSONB        NOT NULL,
    derivation_run_id   UUID,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED' CHECK (status IN
        ('PROPOSED','REJECTED_AUTO','APPROVED','REJECTED','PROMOTED','SUPERSEDED')),
    vet_report          JSONB,
    reviewed_by         VARCHAR(255),
    reviewed_at         TIMESTAMPTZ,
    decision_note       TEXT,
    packs_pr_url        TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (jurisdiction, kind_id, rule_id, proposal_revision),
    CONSTRAINT ck_rule_proposal_reviewed
        CHECK (status NOT IN ('APPROVED','REJECTED','PROMOTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_rule_proposal_queue ON rule_proposal (status, kind_id);

-- Unsupported and unknown kinds, counted: never a document, never its content (ADR-064).
CREATE TABLE IF NOT EXISTS kind_demand (
    jurisdiction  VARCHAR(10) NOT NULL,
    -- A kind id, or UNKNOWN.
    kind_id       VARCHAR(80) NOT NULL,
    count         BIGINT      NOT NULL DEFAULT 0 CHECK (count >= 0),
    first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (jurisdiction, kind_id)
);

-- The law settings (ADR-089 §10). Every switch is off; the numbers are first values, not measurements.
INSERT INTO orazaka_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items) VALUES
('law.corpus.enabled', 'false', 'boolean', 'Master switch of the law corpus (imports, the law-corpus source, lookupStatute on the corpus).', 'false', NULL, NULL, NULL),
('law.import.instruments', 'QC:CCQ-1991,QC:C-11,QC:T-15.01,QC:T-15.01-r1.1,QC:T-15.01-r3,QC:T-0.1,QC:T-0.1-r2,QC:P-39.1,CA:E-15,CA:SOR-91-45', 'list', 'The targeted instruments; nothing else is stored.', 'QC:CCQ-1991,QC:C-11,QC:T-15.01,QC:T-15.01-r1.1,QC:T-15.01-r3,QC:T-0.1,QC:T-0.1-r2,QC:P-39.1,CA:E-15,CA:SOR-91-45', NULL, NULL, 200),
('law.import.max-article-chars', '20000', 'int', 'Longest article text stored; a longer one fails the import loudly.', '20000', 1000, 200000, NULL),
('law.identify.min-signal-weight', '2', 'int', 'Signal weight a detected kind needs to win over the declared one.', '2', 1, 10, NULL),
('law.identify.model-fallback', 'true', 'boolean', 'Ask the model to choose among the kinds when signals are ambiguous (quote checked).', 'true', NULL, NULL, NULL),
('law.identify.classify-max-tokens', '200', 'int', 'Output cap of the classify call.', '200', 50, 1000, NULL),
('law.retrieval.max-articles', '40', 'int', 'Articles one retrieval may keep for a kind.', '40', 1, 200, NULL),
('law.retrieval.max-age-days', '90', 'int', 'A cached retrieval is redone after this even if no cited article changed (new articles may apply).', '90', 7, 365, NULL),
('law.derive.max-rules-per-kind', '30', 'int', 'Rules one derivation may propose.', '30', 1, 100, NULL),
('law.derive.max-credits', '2000', 'int', 'Credit cap of one derivation run, declared into the ADR-088 run budget; 0 = derivation off.', '2000', 0, 100000, NULL),
('law.review.min-golden-cases', '3', 'int', 'Golden documents a kind needs before promotion (one clean, one with errors, the blank form).', '3', 2, 50, NULL),
('law.lecture.enabled', 'false', 'boolean', 'Allow the unaudited lecture for unsupported kinds.', 'false', NULL, NULL, NULL),
('law.lecture.max-credits', '60', 'int', 'Hold of one lecture.', '60', 0, 1000, NULL)
ON CONFLICT (config_key) DO NOTHING;

-- The two sources of the corpus, disabled: law-corpus reads law_article_version (no network, so no pattern);
-- donnees-quebec downloads the Données Québec zips. legisquebec stays disabled above; its URL is the one every
-- citation shows. The Données Québec licence is krizaka/orazaka#393.
INSERT INTO research_source (source_key, kind, provider, base_url, allow_patterns, freshness_seconds, enabled) VALUES
('law-corpus', 'OFFICIAL', 'law-corpus', NULL, '{}', 86400, FALSE),
('donnees-quebec', 'OFFICIAL', 'donnees-quebec', 'https://www.donneesquebec.ca',
 '{www.donneesquebec.ca/recherche/dataset/c8433300-f752-4815-8ea2-69cad416dd80/**}', 604800, FALSE)
ON CONFLICT (source_key) DO NOTHING;

-- The grounded-research capability for Studios (ADR-087 §1, slice 5): a CAPABILITY step on the text lane, like
-- orazaka.core.chat.completion. The model tokens of the answer are billed as CHAT / KILOTOKEN; each research
-- operation (search, fetch, corpus read) is a RESEARCH hold priced by (RESEARCH, <provider key>, CALL) and counted
-- in the run budget by the engine (ResearchMeter, ADR-088), so no new billable_capability exists here. The inputs
-- are the ones orazaka-packs' document-validation sends (sources, jurisdiction, asOf, language, question, targets,
-- document); the output contract is the citation shape of orazaka-studio CitationContract. Nothing calls it while
-- law.lecture.enabled and research.enabled are false (both seeded off). The engine's executor for handler_key
-- research.grounded is orazaka-ai-engine's to register; until then ExecutorCoherenceService reports it at startup.
INSERT INTO orazaka_capabilities
    (feature_key, handler_key, routing_key,
     billable_unit, billable_capability, latency_class, input_schema, output_schema, is_enabled) VALUES
('orazaka.core.research.grounded', 'research.grounded', 'job.text.process', 'KILOTOKEN', 'CHAT', 'INTERACTIVE',
  $${"type": "object", "properties": {"sources": {"type": "array", "items": {"type": "string"}}, "jurisdiction": {"type": "string"}, "asOf": {"type": "string", "format": "date"}, "language": {"type": "string", "enum": ["fr", "en"]}, "question": {"type": "string", "format": "prose"}, "targets": {"type": "array"}, "document": {"type": "string"}}, "required": ["question"]}$$::jsonb,
  $${"type": "object", "required": ["answer", "claims", "sources"], "properties": {"answer": {"type": "string"}, "claims": {"type": "array", "items": {"type": "object", "required": ["text", "status", "citations"], "properties": {"text": {"type": "string", "minLength": 1}, "status": {"type": "string", "enum": ["VERIFIED", "TO_VALIDATE"]}, "reason": {"type": "string"}, "citations": {"type": "array", "items": {"type": "object", "required": ["url", "quote", "contentHash", "fetchedAt"], "properties": {"url": {"type": "string", "minLength": 8}, "quote": {"type": "string", "minLength": 1, "maxLength": 300}, "contentHash": {"type": "string", "minLength": 64, "maxLength": 64}, "fetchedAt": {"type": "string"}}}}}}}, "sources": {"type": "array", "items": {"type": "object", "required": ["url", "title", "sourceKey"], "properties": {"url": {"type": "string"}, "title": {"type": "string"}, "sourceKey": {"type": "string"}}}}}}$$::jsonb, true)
ON CONFLICT (feature_key) DO NOTHING;

-- ============================================================================
-- EXTERNAL CHECKS (orazaka-packs#138, orazaka-ai-engine#90) — court records, credit and identity as engine
-- capabilities a blueprint step names by feature key. The engine's ExternalCheckService is the one door (consent,
-- key, hold, provider, audit, in that order); this service runs it in-process through the executor whose
-- handlerKey() is 'check.external' (ExternalCheckStrategy). Still on the mock connector until the pilot's go.
-- The same block is the dev-deploy migration `external-checks` (krizaka/orazaka): additive and idempotent
-- (constraint replaced by name, IF NOT EXISTS, ON CONFLICT DO NOTHING), the two must stay in step;
-- ExternalCheckSchemaIT applies it twice on a database that predates it. Runs in orazaka_db.
--
-- The subject pepper (setting checks.subject-pepper, sealed) is deliberately NOT seeded: no row, no check. An
-- operator seals one (orazaka-ai-engine, CheckSubjectPepperTool); until then every check job fails closed.
-- ============================================================================

-- billable_capability EXTERNAL_CHECK, priced by the (EXTERNAL_CHECK, <provider>, CALL) rows of credit_pricebook
-- (krizaka-billing#57), and NONE: declared not billable on its own (orazaka-packs#162). A database created before this block has the column's unnamed CHECK without it.
ALTER TABLE orazaka_capabilities DROP CONSTRAINT IF EXISTS orazaka_capabilities_billable_capability_check;
ALTER TABLE orazaka_capabilities DROP CONSTRAINT IF EXISTS ck_orazaka_capabilities_billable_capability;
ALTER TABLE orazaka_capabilities ADD CONSTRAINT ck_orazaka_capabilities_billable_capability
    CHECK (billable_capability IN ('CHAT','IMAGE','AUDIO','VIDEO','AGENT','EXTERNAL_CHECK','NONE'));

-- The audit of the checks (the engine's CheckAudit): one line per call, refused or not. No name, no birth date,
-- no address, no record content: the subject is a keyed HMAC-SHA-256 of its opaque id (subject_ref), the provider
-- reference is the provider's opaque case id. Append-only: a line is never rewritten (the trigger refuses an
-- UPDATE); deleting old lines stays possible for a retention sweep.
CREATE TABLE IF NOT EXISTS external_check_audit (
    id            BIGSERIAL    PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL,
    actor_id      VARCHAR(255),
    run_id        UUID,
    provider      VARCHAR(60)  NOT NULL,
    purpose       VARCHAR(20)  NOT NULL CHECK (purpose IN ('COURT_RECORDS','CREDIT','IDENTITY')),
    key_source    VARCHAR(10)  CHECK (key_source IN ('USER','PLATFORM')),
    outcome       VARCHAR(20)  NOT NULL CHECK (outcome IN ('MATCH','NO_MATCH','PROVIDER_ERROR','TIMEOUT','REFUSED')),
    refusal       VARCHAR(20)  CHECK (refusal IN ('NO_CONSENT','NO_KEY','BUDGET_EXHAUSTED')),
    subject_ref   CHAR(64)     NOT NULL,
    duration_ms   BIGINT,
    provider_ref  VARCHAR(120),
    CONSTRAINT ck_external_check_audit_refusal CHECK ((outcome = 'REFUSED') = (refusal IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_external_check_audit_run ON external_check_audit (run_id);
CREATE INDEX IF NOT EXISTS idx_external_check_audit_actor_at ON external_check_audit (actor_id, at DESC);

CREATE OR REPLACE FUNCTION external_check_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'external_check_audit is append-only: a line is never rewritten';
END
$fn$;
DROP TRIGGER IF EXISTS trg_external_check_audit_append_only ON external_check_audit;
CREATE TRIGGER trg_external_check_audit_append_only BEFORE UPDATE ON external_check_audit
    FOR EACH ROW EXECUTE FUNCTION external_check_audit_append_only();

-- The three checks. One handler for the three: the purpose is the feature key (CheckPurpose.ofFeatureKey), so the
-- executor never branches on a key of its own. routing_key job.text.process, INTERACTIVE: the check runs in this
-- process (job.text.* is drained here) and its duration is the provider's answer time, not something the user
-- supplied. billable_unit CALL: one call when the provider answered (MATCH, NO_MATCH), none otherwise
-- (consumption {calls: 0|1}; CallConsumption releases the hold on 0). The contract is orazaka-packs'
-- tests/contracts/orazaka-core-checks.json: inputs subject + consentedPurposes (a purpose is granted only when
-- listed), outputs outcome, refusal, category, providerRef, calls and resultJson (the same object as a JSON string).
INSERT INTO orazaka_capabilities
    (feature_key, handler_key, routing_key,
     billable_unit, billable_capability, latency_class, input_schema, output_schema, is_enabled) VALUES
('orazaka.core.checks.courtrecords', 'check.external', 'job.text.process', 'CALL', 'EXTERNAL_CHECK', 'INTERACTIVE',
  $${"type": "object", "properties": {"subject": {"type": "object", "properties": {"subjectId": {"type": "string", "minLength": 1}, "lastName": {"type": "string", "minLength": 1}, "firstName": {"type": "string", "minLength": 1}, "birthDate": {"type": "string", "format": "date"}, "address": {"type": "string"}}, "required": ["subjectId", "lastName", "firstName"], "description": "The person checked: an opaque id, the names searched, optionally a birth date and an address."}, "consentedPurposes": {"type": "array", "items": {"type": "string"}, "description": "The purposes the subject consented to: COURT_RECORDS grants this check, nothing else does."}}, "required": ["subject", "consentedPurposes"]}$$::jsonb,
  $${"type": "object", "properties": {"outcome": {"type": "string", "enum": ["MATCH", "NO_MATCH", "PROVIDER_ERROR", "TIMEOUT", "REFUSED"]}, "refusal": {"type": ["string", "null"], "enum": ["NO_CONSENT", "NO_KEY", "BUDGET_EXHAUSTED", null]}, "category": {"type": "string", "enum": ["TAL_NON_PAYMENT", "TAL_OTHER", "CRIMINAL", "CIVIL", "NONE"]}, "providerRef": {"type": ["string", "null"]}, "calls": {"type": "integer", "minimum": 0, "maximum": 1}, "resultJson": {"type": "string"}}, "required": ["outcome", "category", "calls", "resultJson"]}$$::jsonb, true),
('orazaka.core.checks.credit', 'check.external', 'job.text.process', 'CALL', 'EXTERNAL_CHECK', 'INTERACTIVE',
  $${"type": "object", "properties": {"subject": {"type": "object", "properties": {"subjectId": {"type": "string", "minLength": 1}, "lastName": {"type": "string", "minLength": 1}, "firstName": {"type": "string", "minLength": 1}, "birthDate": {"type": "string", "format": "date"}, "address": {"type": "string"}}, "required": ["subjectId", "lastName", "firstName"], "description": "The person checked: an opaque id, the names searched, optionally a birth date and an address."}, "consentedPurposes": {"type": "array", "items": {"type": "string"}, "description": "The purposes the subject consented to: CREDIT grants this check, nothing else does."}}, "required": ["subject", "consentedPurposes"]}$$::jsonb,
  $${"type": "object", "properties": {"outcome": {"type": "string", "enum": ["MATCH", "NO_MATCH", "PROVIDER_ERROR", "TIMEOUT", "REFUSED"]}, "refusal": {"type": ["string", "null"], "enum": ["NO_CONSENT", "NO_KEY", "BUDGET_EXHAUSTED", null]}, "category": {"type": "string", "enum": ["TAL_NON_PAYMENT", "TAL_OTHER", "CRIMINAL", "CIVIL", "NONE"]}, "providerRef": {"type": ["string", "null"]}, "calls": {"type": "integer", "minimum": 0, "maximum": 1}, "resultJson": {"type": "string"}}, "required": ["outcome", "category", "calls", "resultJson"]}$$::jsonb, true),
('orazaka.core.checks.identity', 'check.external', 'job.text.process', 'CALL', 'EXTERNAL_CHECK', 'INTERACTIVE',
  $${"type": "object", "properties": {"subject": {"type": "object", "properties": {"subjectId": {"type": "string", "minLength": 1}, "lastName": {"type": "string", "minLength": 1}, "firstName": {"type": "string", "minLength": 1}, "birthDate": {"type": "string", "format": "date"}, "address": {"type": "string"}}, "required": ["subjectId", "lastName", "firstName"], "description": "The person checked: an opaque id, the names searched, optionally a birth date and an address."}, "consentedPurposes": {"type": "array", "items": {"type": "string"}, "description": "The purposes the subject consented to: IDENTITY grants this check, nothing else does."}}, "required": ["subject", "consentedPurposes"]}$$::jsonb,
  $${"type": "object", "properties": {"outcome": {"type": "string", "enum": ["MATCH", "NO_MATCH", "PROVIDER_ERROR", "TIMEOUT", "REFUSED"]}, "refusal": {"type": ["string", "null"], "enum": ["NO_CONSENT", "NO_KEY", "BUDGET_EXHAUSTED", null]}, "category": {"type": "string", "enum": ["TAL_NON_PAYMENT", "TAL_OTHER", "CRIMINAL", "CIVIL", "NONE"]}, "providerRef": {"type": ["string", "null"]}, "calls": {"type": "integer", "minimum": 0, "maximum": 1}, "resultJson": {"type": "string"}}, "required": ["outcome", "category", "calls", "resultJson"]}$$::jsonb, true)
ON CONFLICT (feature_key) DO NOTHING;
