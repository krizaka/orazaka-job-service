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
    billable_capability VARCHAR(20) NOT NULL DEFAULT 'CHAT'
                        CHECK (billable_capability IN ('CHAT','IMAGE','AUDIO','VIDEO','AGENT')),
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
    description TEXT
);

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
('localai-image', 'http://localhost:8086/v1', 'not-required')
ON CONFLICT (name) DO NOTHING;

INSERT INTO orazaka_models (model_name, model_label, category, options, provider_name, is_default, max_steps, recommended_fps, supported_hardware) VALUES
-- Speech Models
('piper-en-low', 'Piper Low (en)', 'speech', 'Ryan,Low', 'localai', FALSE, NULL, NULL, NULL),
('piper-en-medium-ryan', 'Piper Ryan (en)', 'speech', 'Ryan,Medium', 'localai', TRUE, NULL, NULL, NULL),
('piper-fr-medium', 'Piper Medium (fr)', 'speech', 'Medium,Fr', 'localai', FALSE, NULL, NULL, NULL),
('tts-1', 'OpenAI TTS-1', 'speech', 'Alloy,Echo,Fable,Onyx,Nova,Shimmer', 'localai', FALSE, NULL, NULL, NULL),
-- Image Models
('sdxl-turbo-gguf', 'SDXL Turbo (GGUF)', 'image', NULL, 'localai-image', FALSE, NULL, NULL, NULL),
('sd-1.5-apple-coreml', 'SD 1.5 (Apple CoreML)', 'image', NULL, 'localai-image', FALSE, NULL, NULL, NULL),
('stable-diffusion-xl', 'Stable Diffusion XL', 'image', NULL, 'localai-image', TRUE, NULL, NULL, NULL),
('v1-5-pruned-emaonly', 'SD 1.5 Pruned EMA (safetensors)', 'image', NULL, 'localai-image', FALSE, NULL, NULL, 'APPLE_SILICON_MLX'),
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
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose", "description": "What must happen on screen."}, "durationSeconds": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5}, "image": {"type": "string", "format": "asset-id", "description": "An uploaded still to start from (img2vid)."}, "model": {"type": "string"}}, "required": ["prompt"]}$$::jsonb,
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
('orazaka.core.media.image', 'image.generate', 'job.media.generate', 'IMAGE_STEP', 'IMAGE', 'BATCH',
  $${"type": "object", "properties": {"prompt": {"type": "string", "format": "prose"}, "size": {"type": "string", "default": "1024x1024"}, "model": {"type": "string"}}, "required": ["prompt"]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "format": {"type": "string"}}, "required": ["url"]}$$::jsonb, true),
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
-- overlay → 9:16 MP4. It is a CAPABILITY, not Studio code — the existing Python
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
  $${"type": "object", "properties": {"photos": {"type": "array", "items": {"type": "string", "format": "asset-id"}, "minItems": 1}, "audio": {"type": "string", "format": "asset-id"}}, "required": ["photos"]}$$::jsonb,
  $${"type": "object", "properties": {"url": {"type": "string"}, "assetId": {"type": "string"}, "format": {"type": "string"}, "metrics": {"type": "object"}}, "required": ["url"]}$$::jsonb, true)
ON CONFLICT (feature_key) DO NOTHING;

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
('UserContextInterceptor', 'User Context Interceptor', 11, TRUE, 'Injects onboarding profile (industry, ai_behavior) as system constraints at inference')
ON CONFLICT (interceptor_key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Model descriptions (surfaced in the playground model selector). Kept as
-- UPDATEs so the INSERT rows above stay readable; one line per model_name.
-- ----------------------------------------------------------------------------
UPDATE orazaka_models SET description = 'Fast, lightweight English neural TTS (Piper). Low fidelity, minimal memory — quick local voice checks.' WHERE model_name = 'piper-en-low';
UPDATE orazaka_models SET description = 'Balanced English neural TTS (Piper, Ryan voice). Natural prosody at modest CPU cost. Local speech default.' WHERE model_name = 'piper-en-medium-ryan';
UPDATE orazaka_models SET description = 'French neural TTS (Piper). Natural French speech, medium quality, CPU-friendly.' WHERE model_name = 'piper-fr-medium';
UPDATE orazaka_models SET description = 'OpenAI-compatible TTS with six selectable voices (Alloy, Echo, Fable, Onyx, Nova, Shimmer), served locally via LocalAI.' WHERE model_name = 'tts-1';
UPDATE orazaka_models SET description = 'SDXL Turbo (GGUF) — single-step image generation. Very fast previews, lower detail.' WHERE model_name = 'sdxl-turbo-gguf';
UPDATE orazaka_models SET description = 'Stable Diffusion 1.5 compiled for Apple CoreML. Efficient on-device images on Apple Silicon.' WHERE model_name = 'sd-1.5-apple-coreml';
UPDATE orazaka_models SET description = 'Stable Diffusion XL — high-detail 1024px images. Local image default; heavier than SD 1.5.' WHERE model_name = 'stable-diffusion-xl';
UPDATE orazaka_models SET description = 'Stable Diffusion 1.5 (pruned EMA, safetensors). Compact classic model; runs via Apple MLX.' WHERE model_name = 'v1-5-pruned-emaonly';
UPDATE orazaka_models SET description = 'FLUX.1 Schnell, 4-bit quantized for Apple MLX. Fast, high-quality images natively on Apple Silicon.' WHERE model_name = 'argmaxinc/mlx-FLUX.1-schnell-4bit-quantized';
UPDATE orazaka_models SET description = 'Stable Video Diffusion XT — highest-quality image-to-video. Heavy: needs >64GB unified memory or CUDA. Not recommended on a 64GB Mac.' WHERE model_name = 'stable-video-diffusion-img2vid-xt';
UPDATE orazaka_models SET description = 'AnimateDiff Lightning on Apple Metal (MPS). Fast 8-step text-to-video that fits a 64GB Mac. Local video default.' WHERE model_name = 'animatediff-lightning-mps';
UPDATE orazaka_models SET description = 'Apple CoreML video pipeline. Native Apple Silicon acceleration for short clips.' WHERE model_name = 'apple-coreml-video-pipeline';
UPDATE orazaka_models SET description = 'AnimateDiff Lightning on Apple MLX. Native Apple-Silicon 8-step video at ~12fps.' WHERE model_name = 'mlx-animatediff-lightning';
UPDATE orazaka_models SET description = 'Stable Diffusion video on Apple MLX. Higher quality (25 steps, ~14fps); heavier than AnimateDiff.' WHERE model_name = 'mlx-stable-diffusion-video';
UPDATE orazaka_models SET description = 'SVD XT in PyTorch MPS float32. Highest fidelity but ~96GB memory — will fail on a 64GB Mac.' WHERE model_name = 'stable-video-diffusion-img2vid-xt-mps-fp32';
UPDATE orazaka_models SET description = 'ByteDance AnimateDiff Lightning weights (MLX). Fast 8-step text-to-video at ~12fps.' WHERE model_name = 'ByteDance/AnimateDiff-Lightning';
UPDATE orazaka_models SET description = 'LLaVA multimodal model — describe and answer questions about images.' WHERE model_name = 'llava:latest';
UPDATE orazaka_models SET description = 'LLaVA v1.6 — improved image understanding and OCR over base LLaVA.' WHERE model_name = 'llava:v1.6';
UPDATE orazaka_models SET description = 'BakLLaVA (Mistral-based) vision model. Compact image understanding.' WHERE model_name = 'bakllava:latest';
UPDATE orazaka_models SET description = 'Llama 3.2 Vision — strong general image understanding. Local vision default.' WHERE model_name = 'llama3.2-vision:latest';
UPDATE orazaka_models SET description = 'Whisper Base speech-to-text. Balanced accuracy and speed for transcription. Local audio default.' WHERE model_name = 'whisper-base';
UPDATE orazaka_models SET description = 'Whisper Tiny (English) STT. Fastest, smallest footprint — quick English transcriptions.' WHERE model_name = 'whisper-tiny-en';
UPDATE orazaka_models SET description = 'Qwen 2.5 Coder 7B — code generation and completion (q4_K_M). Local code default.' WHERE model_name = 'qwen2.5-coder:7b';
UPDATE orazaka_models SET description = 'Code Llama 7B — code generation and explanation (q4_K_M).' WHERE model_name = 'codellama:7b';
UPDATE orazaka_models SET description = 'TinyLlama — tiny, fast chat model for local testing. Limited reasoning.' WHERE model_name = 'tinyllama:latest';


-- ── MCP registry + tool cache/config (router-hosted MCP execution; Phase 4 kept the
--    RAG *sources* out — those moved to the knowledge service's own database) ──────────
CREATE TABLE user_mcp_servers (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    label VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    auth_token VARCHAR(1000),
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
    auth_token VARCHAR(1000),
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
('searchWeb', TRUE, 3600, TRUE, 'MARKDOWN_CHUNKERS', 'orazaka_tools_rag_source')
ON CONFLICT (tool_id) DO NOTHING;
