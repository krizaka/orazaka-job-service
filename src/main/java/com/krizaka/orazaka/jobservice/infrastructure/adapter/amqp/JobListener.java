package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import com.krizaka.billing.domain.model.ConsumptionReport;
import com.krizaka.billing.domain.model.UnmeteredTurn;
import com.krizaka.billing.domain.port.UnmeteredTurnRepository;
import com.krizaka.messaging.dedup.MessageDedup;
import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.core.application.pipeline.PipelineShortCircuitException;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.krizaka.orazaka.jobs.domain.model.FailureCause;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import com.krizaka.orazaka.jobservice.application.service.ContextService;
import com.krizaka.orazaka.jobservice.infrastructure.config.JobsProperties;
import com.krizaka.orazaka.jobservice.infrastructure.support.AssetFileResolver;
import com.krizaka.orazaka.jobservice.infrastructure.support.PathResolver;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.krizaka.orazaka.persistence.infrastructure.config.MessagingContract;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Asynchronous RabbitMQ listener that consumes task execution requests.
 *
 * <p>Manages the state of jobs from PENDING → PROCESSING → COMPLETED or FAILED. Delegates
 * feature-specific execution to the registered {@link JobExecutor} whose {@code handlerKey()}
 * matches the capability's {@code handler_key} column. The executors arrive by the
 * AutoConfiguration SPI, so an out-of-tree jar contributes one without touching this class
 * (ADR-038).
 */
@Component
public class JobListener {

  private static final Logger logger = LoggerFactory.getLogger(JobListener.class);
  private static final String STATUS_FAILED = "FAILED";
  private static final String UNDECLARED_DATA_CLASS = "JOB_DATA_CLASS_UNDECLARED";

  /**
   * Payload keys that used to carry a filesystem path, and no longer may. Each is filled here from
   * the id beside it; what a producer put there is dropped (ADR-066).
   */
  private static final List<String> PATH_KEYS = List.of("filePath", "imagePath");

  /** Stable dedup identity of this consumer (AGENTS.md §6 messageId idempotency). */
  private static final String DEDUP_CONSUMER = "job.executor";

  private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

  /**
   * Measurements a strategy may report in its result's {@code metrics} map, mirroring the billing
   * service's ConsumptionReport. Raw measurements only — never a billable unit, which belongs to
   * the pricebook.
   */
  private static final List<String> CONSUMPTION_KEYS =
      List.of(
          "durationSeconds",
          "frames",
          "fps",
          "images",
          "steps",
          "width",
          "height",
          "characters",
          "audioSeconds",
          "tokens");

  private final JobPersistenceProvider jobPersistenceProvider;
  private final JobEventPublisher jobEventPublisher;
  private final Map<String, JobExecutor> executorsByHandler;
  private final CapabilityManager capabilityManager;
  private final String uploadDirProperty;
  private final ExecutorService virtualThreadExecutor;
  private final int jobTimeoutSeconds;
  private final ObjectMapper objectMapper;
  private final JobSimulationHook simulationHook;
  private final UserDirectoryClient userDirectoryService;
  private final ContextService contextService;
  private final MessageDedup messageDedupService;
  private final EncryptedAssetService encryptedAssetService;
  private final UnmeteredTurnRepository unmeteredTurnRepository;

  /**
   * Constructs the job listener with injected strategies and infrastructure dependencies.
   *
   * @param jobPersistenceProvider The job persistence provider for status updates.
   * @param jobEventPublisher Publishes terminal job lifecycle events for the SSE relay.
   * @param strategies All registered job execution strategies (injected by Spring).
   * @param uploadDirProperty The base upload directory path.
   * @param virtualThreadExecutor The virtual thread executor for async job execution.
   * @param jobTimeoutSeconds The maximum execution time per job.
   * @param objectMapper The JSON serializer for payload persistence.
   * @param simulationHook Optional test simulation hook (defaults to no-op in production).
   * @param userDirectoryService Cached HTTP view of the identity service.
   * @param contextService The orchestration-context resolver.
   * @param unmeteredTurnRepository Records a completed job whose unit could not be measured.
   */
  public JobListener(
      JobPersistenceProvider jobPersistenceProvider,
      JobEventPublisher jobEventPublisher,
      List<JobExecutor> executors,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir,
      ExecutorService virtualThreadExecutor,
      JobsProperties jobsProperties,
      ObjectMapper objectMapper,
      Optional<JobSimulationHook> simulationHook,
      UserDirectoryClient userDirectoryService,
      ContextService contextService,
      CapabilityManager capabilityManager,
      MessageDedup messageDedupService,
      EncryptedAssetService encryptedAssetService,
      UnmeteredTurnRepository unmeteredTurnRepository) {
    this.jobPersistenceProvider =
        Objects.requireNonNull(jobPersistenceProvider, "JobPersistenceProvider cannot be null");
    this.jobEventPublisher =
        Objects.requireNonNull(jobEventPublisher, "JobEventPublisher cannot be null");
    Objects.requireNonNull(executors, "Executor list cannot be null");
    Map<String, JobExecutor> byHandler = new HashMap<>();
    for (JobExecutor executor : executors) {
      JobExecutor previous = byHandler.put(executor.handlerKey(), executor);
      if (previous != null) {
        // Refused at startup, not at dispatch: a silently shadowed executor is a job that runs
        // the wrong code, and with an open SPI the collision can now come from a jar nobody in
        // this repository reviewed.
        throw new IllegalStateException(
            "Duplicate JobExecutor handler key '%s': %s and %s"
                .formatted(
                    executor.handlerKey(),
                    previous.getClass().getName(),
                    executor.getClass().getName()));
      }
    }
    this.executorsByHandler = Map.copyOf(byHandler);
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager cannot be null");
    this.uploadDirProperty = PathResolver.resolveToString(uploadDir);
    this.virtualThreadExecutor =
        Objects.requireNonNull(virtualThreadExecutor, "virtualThreadExecutor cannot be null");
    this.jobTimeoutSeconds = jobsProperties.executionTimeout();
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.simulationHook = simulationHook.orElse(JobSimulationHook.NOOP);
    this.userDirectoryService =
        Objects.requireNonNull(userDirectoryService, "UserDirectoryClient cannot be null");
    this.contextService = Objects.requireNonNull(contextService, "ContextService cannot be null");
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService cannot be null");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedup cannot be null");
    this.unmeteredTurnRepository =
        Objects.requireNonNull(unmeteredTurnRepository, "UnmeteredTurnRepository cannot be null");

    logger.info(
        "Initialized JobListener with {} executors: {}",
        executorsByHandler.size(),
        executorsByHandler.keySet().stream().sorted().toList());
  }

  /**
   * Consumes and processes an incoming JobCommand. Deduplicated by AMQP messageId (AGENTS.md §6):
   * redeliveries and dual-publishes during consumer migration are skipped; the message is marked
   * processed once it reaches a terminal outcome (every path below acks), so a crash mid-processing
   * lets the redelivery run again (at-least-once).
   *
   * @param message The deserialized job instruction payload.
   * @param messageId The AMQP message id set by the outbox relay ({@code null} for legacy
   *     publishers — processed without dedup).
   */
  @RabbitListener(
      queues = MessagingContract.JOBS_INTERACTIVE_QUEUE,
      concurrency = "${orazaka.jobs.lanes.interactive-concurrency:2}")
  public void onInteractiveMessage(
      JobCommand message,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    onMessage(message, messageId);
  }

  /**
   * The BATCH lane's pool — its own consumers, so a 67-second image cannot occupy the slot an
   * analysis needs (ADR-067).
   *
   * <p>Two pools, no scheduler: no fair-share, no ageing, no weighted round-robin. What a second
   * lane buys is fairness between tenants, not throughput — the accelerator is still one.
   *
   * @param message the deserialized job instruction payload
   * @param messageId the AMQP message id set by the outbox relay
   */
  @RabbitListener(
      queues = MessagingContract.JOBS_BATCH_QUEUE,
      concurrency = "${orazaka.jobs.lanes.batch-concurrency:1}")
  public void onBatchMessage(
      JobCommand message,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    onMessage(message, messageId);
  }

  public void onMessage(
      JobCommand message,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (!messageDedupService.claim(DEDUP_CONSUMER, messageId)) {
      logger.info("Skipping duplicate job message {} for Job ID: {}", messageId, message.jobId());
      return;
    }
    logger.info(
        "Received job execution message for Job ID: {}, Feature: {}",
        message.jobId(),
        message.featureKey());
    if (message.dataClass() == null) {
      refuseUndeclaredClass(message);
      return;
    }

    adoptIfUnknown(message);
    jobPersistenceProvider.updateJobStatus(message.jobId(), "PROCESSING", null, null);
    // Before anything reads the payload: turn an assetId into the filePath the executors need.
    // A producer that dispatches from a blueprint has no upload root to resolve it with, so
    // until this existed no Studio step consuming an uploaded asset could run at all (ADR-042).
    JobCommand resolved = resolveAssets(message);
    saveInputPayload(resolved);

    int timeoutSeconds = resolveTimeout(resolved);

    try {
      // `resolved`, not `message`: the executors read filePath, and only the resolved command
      // carries it. Passing the original here is what would leave this fix inert.
      CompletableFuture.runAsync(
              () -> executeJobAsync(resolved, timeoutSeconds), virtualThreadExecutor)
          .get(timeoutSeconds, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      handleTimeout(resolved, timeoutSeconds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleInterruption(resolved, e);
    } catch (Exception e) {
      handleExecutionFailure(resolved, e);
    }
  }

  /**
   * Refuses a job whose producer declared no data class, before any of its material is kept.
   *
   * <p>The class chooses how long this plane keeps the job's row and files (ADR-065). Read as
   * STANDARD, a SENSITIVE step from a producer that forgot to declare would be kept indefinitely,
   * and nothing would ever say so. Refused instead: no row is adopted, no input is archived,
   * nothing executes. {@code EXECUTOR_FAULT}, because a missing declaration is the platform's
   * defect and never the user's — it releases the hold and blames nobody (ADR-053). A row the
   * producer already created is closed so the dashboard does not show the job pending forever.
   */
  private void refuseUndeclaredClass(JobCommand message) {
    logger.error(
        "Job {} for feature {} declares no data class; refused before anything was stored",
        message.jobId(),
        message.featureKey());
    if (jobPersistenceProvider.getJob(message.jobId()).isPresent()) {
      jobPersistenceProvider.updateJobStatus(
          message.jobId(), STATUS_FAILED, null, UNDECLARED_DATA_CLASS);
    }
    jobEventPublisher.emitError(
        message.jobId(), message.holdId(), FailureCause.EXECUTOR_FAULT, UNDECLARED_DATA_CLASS);
  }

  /**
   * Creates the job row for a producer that submitted straight onto the exchange.
   *
   * <p>Most producers create the row first and publish second. A Studio step cannot: the studio
   * service owns a different database and must not write this one (SEAM-001), so it publishes a
   * JobCommand with a fresh id and nothing else. Without this, {@code updateJobStatus} would match
   * no row, the execution would still run and still emit its outcome — and the job would be
   * invisible in the jobs dashboard, which is exactly where support looks when a Studio run fails
   * (ADR-034 §14).
   *
   * <p>Idempotent by construction: it only writes when the row is genuinely absent, so a redelivery
   * or a normally-created job is untouched.
   */
  private void adoptIfUnknown(JobCommand message) {
    if (jobPersistenceProvider.getJob(message.jobId()).isPresent()) {
      return;
    }
    jobPersistenceProvider.createJob(
        message.jobId(),
        message.userId(),
        message.featureKey(),
        message.payload(),
        message.dataClass());
    logger.info(
        "Adopted externally-submitted job {} for feature {}",
        message.jobId(),
        message.featureKey());
  }

  private void executeJobAsync(JobCommand message, int timeoutSeconds) {
    try {
      User user = userDirectoryService.getUser(message.userId());
      Context context =
          withProducerPreferences(contextService.resolve(user, message.jobId()), message);

      List<SimpleGrantedAuthority> authorities =
          user.authorities().stream().map(SimpleGrantedAuthority::new).toList();
      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(user, null, authorities);
      SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
      securityContext.setAuthentication(auth);
      SecurityContextHolder.setContext(securityContext);

      try {
        simulationHook.beforeExecution(message, timeoutSeconds);
        long startedAt = System.nanoTime();
        JobExecutionResult executed = dispatchToExecutor(message, context);
        Map<String, Object> result = executed.output();
        Map<String, Object> consumption =
            measureConsumption(startedAt, result, executed.consumption());
        saveOutputResult(message.userId(), message.jobId(), result);
        // Before the job is reported complete: a turn served without a measurement its unit can
        // price is free inference, and free inference is recorded rather than shrugged off
        // (ADR-067, audit #46). Recording first means a failure to record fails the job instead of
        // hiding it, which is the posture ADR-064 chose for the same question.
        recordIfUnmetered(message, consumption);
        jobPersistenceProvider.updateJobStatus(message.jobId(), "COMPLETED", result, null);
        // The executor's resolved model wins over the producer's request: a studio step is
        // dispatched with the "default" sentinel and only the executor knows which engine ran,
        // which is the half of the pricebook key (capability, model) that was missing.
        String executedModel = executed.model() != null ? executed.model() : message.model();
        jobEventPublisher.emitDone(
            message.jobId(), message.holdId(), executedModel, result, consumption);
        logger.info("Successfully completed Job ID: {}", message.jobId());
      } finally {
        SecurityContextHolder.clearContext();
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Records a completed job whose measurement its unit cannot price — free inference, accounted.
   *
   * <p><b>Why {@code UnmeteredTurn} and not a new mechanism.</b> M1.7 wrote it for exactly this
   * question on the credit gate (ADR-064, audit #25): the work is served, the event is recorded,
   * and billing collects it into {@code unmetered_turn} for reconciliation. The transcription
   * fallback of ADR-066 is the same shape one layer down — a provider that refuses {@code
   * verbose_json} is retried plain, the job settles unmeasured, and the hold is released — and
   * before this it left no trace at all (audit #46).
   *
   * <p>Only a job that HELD credits is recorded: without a hold nothing was authorised through
   * billing and there is nothing to reconcile. And only a capability whose unit is known here — one
   * priced per model is billing's judgement, not this listener's.
   */
  private void recordIfUnmetered(JobCommand message, Map<String, Object> consumption) {
    if (message.holdId() == null) {
      return;
    }
    Optional<CapabilityDeclaration> capability =
        capabilityManager.findByFeatureKey(message.featureKey());
    String unit = capability.map(CapabilityDeclaration::billableUnit).orElse(null);
    String billable = capability.map(CapabilityDeclaration::billableCapability).orElse(null);
    if (unit == null || billable == null) {
      return;
    }
    ConsumptionReport report = objectMapper.convertValue(consumption, ConsumptionReport.class);
    if (report.quantityFor(BillableUnit.valueOf(unit)).isPresent()) {
      return;
    }
    logger.warn(
        "Job {} completed with no {} measurement; recording an unmetered turn for reconciliation",
        message.jobId(),
        unit);
    unmeteredTurnRepository.record(
        new UnmeteredTurn(
            message.userId(),
            BillableCapability.valueOf(billable),
            message.correlationId() != null ? message.correlationId() : message.jobId(),
            "executor reported no " + unit + " measurement",
            Instant.now()));
  }

  /**
   * Merges the {@code orazaka.*} keys a producer stamped into the payload onto the resolved
   * context.
   *
   * <p>A job's context is normally the actor's profile. A Studio step additionally carries the
   * installation's brand kit (ADR-034 §9.1), which the pipeline's enrichment interceptors read as
   * ordinary preferences — so the producer's namespaced keys have to reach the same map.
   *
   * <p>Namespaced on purpose, and merged rather than overwritten: a producer must be able to add
   * context without being able to redefine who the actor is or what their profile says.
   */
  private static Context withProducerPreferences(Context context, JobCommand message) {
    Map<String, Object> namespaced =
        message.payload().entrySet().stream()
            .filter(
                entry ->
                    entry.getKey() != null && entry.getKey().startsWith(Context.PLATFORM_NAMESPACE))
            .collect(HashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), HashMap::putAll);
    if (namespaced.isEmpty()) {
      return context;
    }
    Map<String, Object> merged = new HashMap<>(context.preferences());
    merged.putAll(namespaced);
    return new Context(context.userId(), context.conversationId(), merged, context.authorities());
  }

  private void handleTimeout(JobCommand message, int timeoutSeconds) {
    logger.error(
        "Job execution timed out for Job ID: {} after {} seconds", message.jobId(), timeoutSeconds);
    jobPersistenceProvider.updateJobStatus(
        message.jobId(), STATUS_FAILED, null, "EXECUTION_TIMEOUT_EXCEEDED");
    jobEventPublisher.emitError(
        message.jobId(), message.holdId(), FailureCause.TIMEOUT, "EXECUTION_TIMEOUT_EXCEEDED");
  }

  private void handleInterruption(JobCommand message, InterruptedException e) {
    logger.error("Job execution interrupted for Job ID: {}", message.jobId(), e);
    jobPersistenceProvider.updateJobStatus(
        message.jobId(), STATUS_FAILED, null, "EXECUTION_INTERRUPTED");
    // Interruption is the platform stopping this work, not the work being wrong: a shutdown, a
    // pool teardown, a container going away. PLATFORM_UNAVAILABLE, so it releases and blames
    // nobody — and so the audit log says which of our own mornings this was.
    jobEventPublisher.emitError(
        message.jobId(),
        message.holdId(),
        FailureCause.PLATFORM_UNAVAILABLE,
        "EXECUTION_INTERRUPTED");
  }

  private void handleExecutionFailure(JobCommand message, Exception e) {
    PipelineShortCircuitException refusal = refusalIn(e);
    if (refusal != null) {
      // Not a failure: a gate answered. The pack wrote that sentence for the user to read, so it
      // travels verbatim — no class name, no stack trace (ADR-051).
      logger.info(
          "Job {} was refused by '{}': {}",
          message.jobId(),
          refusal.interceptorId(),
          refusal.reason());
      String refusalText = refusal.getMessage();
      saveOutputResult(message.userId(), message.jobId(), Map.of("error", refusalText));
      jobPersistenceProvider.updateJobStatus(message.jobId(), STATUS_FAILED, null, refusalText);
      jobEventPublisher.emitError(
          message.jobId(), message.holdId(), FailureCause.GUARD_REFUSAL, refusalText);
      return;
    }
    logger.error("Error occurred while processing Job ID: {}", message.jobId(), e);
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    String errorMessage = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
    Map<String, Object> errResult = Map.of("error", errorMessage);
    saveOutputResult(message.userId(), message.jobId(), errResult);
    jobPersistenceProvider.updateJobStatus(message.jobId(), STATUS_FAILED, null, errorMessage);
    // EXECUTOR_FAULT, and deliberately not a classifier over `errorMessage`. ADR-046 §2's
    // counter-example is exactly this branch: "compose requires at least one readable photo" read
    // like bad input and was our own defect three layers deep. Anything reaching here is something
    // this executor did not anticipate, and not anticipating it is our fault by definition.
    jobEventPublisher.emitError(message.jobId(), message.holdId(), causeOf(e), errorMessage);
  }

  /**
   * The typed cause of a failure this executor did not declare itself (ADR-053).
   *
   * <p>Only two things are read from the exception, and both are structural rather than textual: a
   * dependency this executor could not reach is {@code PLATFORM_UNAVAILABLE}, and everything else
   * is {@code EXECUTOR_FAULT}. There is no branch that produces {@code INPUT_INVALID} here — that
   * cause must come from a validator that <i>declared</i> the payload unusable, never from an
   * exception that merely happened while user data was in scope.
   */
  private static FailureCause causeOf(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      // The operator switched the engine off (ADR-062). Our decision, not the user's input and not
      // a gate's refusal: it releases the hold and blames nobody, like any platform outage.
      if (current
          instanceof com.krizaka.orazaka.core.application.pipeline.PipelineDisabledException) {
        return FailureCause.PLATFORM_UNAVAILABLE;
      }
      if (current instanceof java.net.ConnectException
          || current instanceof java.net.UnknownHostException
          || current instanceof java.net.SocketTimeoutException
          || current instanceof org.springframework.web.client.ResourceAccessException) {
        return FailureCause.PLATFORM_UNAVAILABLE;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return FailureCause.EXECUTOR_FAULT;
  }

  /**
   * The refusal in a throwable chain, or {@code null} if this is an ordinary failure.
   *
   * <p>Walks the chain rather than testing the top: by the time an execution failure reaches here
   * the refusal is usually wrapped once or twice by the executor that ran the turn.
   */
  private static PipelineShortCircuitException refusalIn(Throwable t) {
    for (Throwable current = t; current != null; current = current.getCause()) {
      if (current instanceof PipelineShortCircuitException refusal) {
        return refusal;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return null;
  }

  /**
   * Consumes failed dead-lettered job messages from the per-queue DLQs (AGENTS.md §6: {@code
   * <queue>.dlq}).
   *
   * @param message The dead-lettered job instruction payload.
   */
  @RabbitListener(
      queues = {MessagingContract.JOBS_INTERACTIVE_DLQ, MessagingContract.JOBS_BATCH_DLQ})
  public void onDeadLetter(JobCommand message) {
    logger.warn(
        "Received dead-lettered job message for Job ID: {}, Feature: {}",
        message.jobId(),
        message.featureKey());
    jobPersistenceProvider.updateJobStatus(
        message.jobId(),
        STATUS_FAILED,
        null,
        "Job dead-lettered due to processing failure after max retry attempts");
    // A message that exhausted its retries and reached the DLQ tells us the executor could not
    // finish it, and nothing about why. EXECUTOR_FAULT is the honest reading, and it is also the
    // reading that releases: whatever went wrong here, we could not name it (ADR-053).
    jobEventPublisher.emitError(
        message.jobId(),
        message.holdId(),
        FailureCause.EXECUTOR_FAULT,
        "Job dead-lettered due to processing failure after max retry attempts");
  }

  /**
   * Measures what this execution consumed, for the settlement of its hold (ADR-033 §6.3).
   *
   * <p>Measurements only — the billable unit belongs to the pricebook row the hold was pinned to
   * and is resolved by the billing service. Wall clock is reported for every job because on owned
   * hardware the accelerator is occupied for the whole execution, which makes it the honest cost
   * signal and the basis for calibrating guessed rates into measured ones (design §7).
   *
   * <p>Strategy-specific measurements are lifted from the result's {@code metrics} map when a
   * strategy reports them; a strategy that reports none simply yields no priceable quantity, and an
   * unmeasured job is released rather than billed at its estimate.
   */
  private static Map<String, Object> measureConsumption(
      long startedAtNanos, Map<String, Object> result, Map<String, Number> reported) {
    Map<String, Object> consumption = new HashMap<>();
    consumption.put(
        "gpuSeconds",
        BigDecimal.valueOf(System.nanoTime() - startedAtNanos)
            .divide(NANOS_PER_SECOND, 3, RoundingMode.HALF_UP));
    if (result != null && result.get("metrics") instanceof Map<?, ?> metrics) {
      for (String key : CONSUMPTION_KEYS) {
        Object value = metrics.get(key);
        if (value instanceof Number) {
          consumption.put(key, value);
        }
      }
    }
    // The typed channel wins over the legacy metrics map: an executor that measured explicitly
    // knows better than a key lifted out of its output. Both are kept because the Python worker
    // reports through `metrics` on the wire and cannot use the Java port at all.
    //
    // It wins only inside the measurement vocabulary, the same one the metrics map is read through.
    // gpuSeconds is not in it: that is this listener's own measurement, and ADR-038 opened the
    // executor port to jars nobody here reviewed — a report merged whole let any of them replace
    // the
    // occupancy the GPU_SECOND rate and the margin are computed from (ADR-064).
    CONSUMPTION_KEYS.stream()
        .filter(key -> reported.get(key) != null)
        .forEach(key -> consumption.put(key, reported.get(key)));
    return consumption;
  }

  /**
   * Resolves the job's capability from the database and routes to the strategy registered under the
   * capability's {@code handler_key}. Dispatch is DB-driven — no Java-side ordering or feature-key
   * heuristics.
   */
  private JobExecutionResult dispatchToExecutor(JobCommand message, Context context)
      throws JobExecutionException {
    var capability =
        capabilityManager
            .findByFeatureKey(message.featureKey())
            .orElseThrow(
                () ->
                    new JobExecutionException(
                        "Unknown capability for feature key: " + message.featureKey()));
    JobExecutor executor = executorsByHandler.get(capability.handlerKey());
    if (executor == null) {
      throw new JobExecutionException(
          "No executor registered for handler key '%s' (feature key: %s). Registered: %s"
              .formatted(
                  capability.handlerKey(),
                  message.featureKey(),
                  executorsByHandler.keySet().stream().sorted().toList()));
    }
    return executor.execute(message, toExecutionContext(context));
  }

  /**
   * Narrows the engine context to the Tier-1 shape the executor port accepts.
   *
   * <p>The port cannot carry {@code Context}: that would put {@code orazaka-core} on the classpath
   * of every out-of-tree executor (ADR-038). The listener resolves the engine context as before and
   * narrows it here — the engine type stops at this boundary.
   */
  private static JobExecutionContext toExecutionContext(Context context) {
    return new JobExecutionContext(
        context.userId(),
        context.conversationId(),
        context.preferences(),
        context.authorities().stream()
            .map(a -> a.name())
            .collect(java.util.stream.Collectors.toSet()));
  }

  private int resolveTimeout(JobCommand message) {
    String prompt = message.prompt();
    if (prompt != null && prompt.startsWith("TIMEOUT")) {
      return 5;
    }
    return jobTimeoutSeconds;
  }

  /**
   * Turns the ids a payload names into the paths its executors read — and removes the paths it
   * carries.
   *
   * <p><b>The job plane reads no path a producer sends.</b> The media worker was closed against
   * absolute paths in M1.8 and this side was still open: `POST /api/v1/jobs` passes its payload
   * through verbatim, so any authenticated caller could name a file by location and have the
   * executor read it and this listener archive it (audit #32). Removing the possibility rather than
   * validating it is the same call as the worker's — an owner check would leave an expressiveness
   * nothing needs. It does not close the {@code orazaka.*} key hole on that endpoint, which is M3.
   *
   * <p>The producers were already resolving server-side and each now passes the id it resolved:
   * {@code MediaAnalysisController} answers 404 for an asset that is not the caller's, and the
   * video generator hands over the image's id.
   *
   * <p>The producer-side counterpart is {@code MediaAnalysisController}, which resolves an asset
   * and hands {@code JobSubmissionService} a file. A Studio dispatch cannot do that — {@code
   * AmqpStepExecutionAdapter} copies a step's resolved inputs verbatim, and the studio context has
   * no upload root — so a blueprint step declaring {@code assetId} arrived with no {@code filePath}
   * and {@code VisionAnalysisStrategy} refused it. Every asset-consuming Studio step failed,
   * always, and {@code onError: SKIP} on the one fixture that exercised it turned that into a green
   * run.
   *
   * <p>Resolution is scoped to {@link JobCommand#userId()} — the acting user of THIS job, never a
   * value read out of the payload — so a blueprint naming another actor's asset resolves to
   * nothing. An id that cannot be resolved is left alone rather than blanked: the executor's own
   * "missing filePath" failure names the problem better than a silently emptied payload, and a
   * capability that never wanted a file is unaffected.
   *
   * @param message the incoming command
   * @return the command with {@code filePath} filled in, or the original when there is nothing to
   *     resolve
   */
  private JobCommand resolveAssets(JobCommand message) {
    Map<String, Object> payload = message.payload();
    // Every asset id in the payload, wherever it sits: `assetId` for the single-image capabilities,
    // and list-valued inputs like a composition's `photos` (ADR-046).
    Map<String, Object> resolved =
        AssetFileResolver.resolvePayload(uploadDirProperty, message.userId(), payload);

    // A path in the payload is not an asset reference. Dropped before anything reads it, and not
    // checked against the actor's root: an owner check would keep a way of naming files by
    // location, which no producer needs and which `POST /api/v1/jobs` lets an end user write
    // (ADR-066, audit #32).
    for (String pathKey : PATH_KEYS) {
      if (payload.get(pathKey) != null) {
        if (resolved == payload) {
          resolved = new HashMap<>(resolved);
        }
        resolved.remove(pathKey);
        logger.warn(
            "Job {}: dropped the payload's '{}'; assets are named by id and resolved here",
            message.jobId(),
            pathKey);
      }
    }

    // The ids come from what the producer sent: `resolvePayload` above may already have rewritten
    // an id-shaped value into a path, and looking one up in the rewritten map would resolve
    // nothing.
    resolved = withResolvedPath(message, resolved, payload.get("assetId"), "filePath");
    resolved = withResolvedPath(message, resolved, payload.get("image"), "imagePath");

    if (resolved == payload) {
      return message;
    }
    return new JobCommand(
        message.jobId(),
        message.userId(),
        message.featureKey(),
        message.model(),
        resolved,
        message.dataClass());
  }

  /**
   * Fills {@code pathKey} from the asset the producer named, resolved against this job's actor.
   *
   * <p>The actor is {@link JobCommand#userId()} — the job's own, never a value out of the payload —
   * so an id belonging to somebody else resolves to nothing. Not found and not yours are the same
   * answer here, as they are at {@code /analyze/*} (404) and in the media worker's twin: which of
   * the two it was is exactly what enumeration asks.
   */
  private Map<String, Object> withResolvedPath(
      JobCommand message, Map<String, Object> payload, Object assetId, String pathKey) {
    if (assetId == null) {
      return payload;
    }
    Optional<File> asset = AssetFileResolver.resolve(uploadDirProperty, message.userId(), assetId);
    if (asset.isEmpty()) {
      logger.warn(
          "Job {}: the asset named for '{}' is not user {}'s; the step fails on the missing file"
              + " rather than read someone else's",
          message.jobId(),
          pathKey,
          message.userId());
      return payload;
    }
    Map<String, Object> withPath = new HashMap<>(payload);
    withPath.put(pathKey, asset.get().getAbsolutePath());
    logger.info(
        "Job {}: resolved the asset for {} of user {}", message.jobId(), pathKey, message.userId());
    return withPath;
  }

  private void saveInputPayload(JobCommand message) {
    String userId = message.userId();
    String jobId = message.jobId();
    Map<String, Object> payload = message.payload();
    if (payload == null) {
      return;
    }
    try {
      Path inputDir =
          Paths.get(uploadDirProperty, userId, jobId, "input").toAbsolutePath().normalize();
      Files.createDirectories(inputDir);
      Path inputJsonPath = inputDir.resolve("input.json");
      byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
      // A job's input payload is the user's own words and, for a document capability, the
      // document itself. Encrypted like every other asset (ADR-054).
      encryptedAssetService.write(inputJsonPath, jsonBytes);
      logger.info("Saved job input payload to: {}", inputJsonPath);

      String filePath = (String) payload.get("filePath");
      if (filePath != null && !filePath.isBlank()) {
        File file = new File(filePath);
        if (file.exists()) {
          String filename = resolveInputFilename(message, file);
          Path inputFilePath = inputDir.resolve(filename);
          try (InputStream source = Files.newInputStream(file.toPath())) {
            encryptedAssetService.write(inputFilePath, source, file.length());
          }
          logger.info("Copied input media file to: {}", inputFilePath);
        }
      }
    } catch (Exception e) {
      logger.error("Failed to save input payload or media to file", e);
    }
  }

  /**
   * Names the archived copy of a job's input from the file itself, never from the capability.
   *
   * <p>This was a {@code contains()} chain over the feature key — {@code "video"} → {@code .mp4},
   * {@code "audio"} → {@code .mp3} — the fourth and last of the substring chains ADR-037 set out to
   * remove. It was the mildest of them (it names an archive copy, and its fallback already
   * preserved the real extension), but it is the idiom [PACK-003] exists to kill, and it would have
   * given a pack's document capability an {@code input.bin} while a chat capability carrying a PNG
   * got {@code .mp4} for containing the word "video".
   *
   * <p>The producer already knows the answer: {@code JobSubmissionService} probes the content type
   * and puts {@code mimeType} in the payload. Reading it means the name follows the bytes rather
   * than the capability's spelling. The extension of the uploaded file remains the fallback, and it
   * was always the more reliable branch.
   */
  private static String resolveInputFilename(JobCommand message, File file) {
    String extension = extensionForMimeType(message.payload().get("mimeType"));
    if (extension != null) {
      return "input" + extension;
    }
    String name = file.getName();
    int dotIndex = name.lastIndexOf('.');
    if (dotIndex > 0 && dotIndex < name.length() - 1) {
      return "input" + name.substring(dotIndex);
    }
    return "input.bin";
  }

  /**
   * The canonical extension for a reported MIME type, or {@code null} when it is not one we name.
   */
  private static String extensionForMimeType(Object mimeType) {
    if (!(mimeType instanceof String type) || type.isBlank()) {
      return null;
    }
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "video/mp4" -> ".mp4";
      case "audio/mpeg" -> ".mp3";
      case "audio/wav", "audio/x-wav" -> ".wav";
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      case "application/pdf" -> ".pdf";
      default -> null;
    };
  }

  private void saveOutputResult(String userId, String jobId, Map<String, Object> result) {
    if (result == null) {
      return;
    }
    try {
      Path outputDir =
          Paths.get(uploadDirProperty, userId, jobId, "output").toAbsolutePath().normalize();
      Files.createDirectories(outputDir);
      Path outputJsonPath = outputDir.resolve("result.json");
      byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
      encryptedAssetService.write(outputJsonPath, jsonBytes);
      logger.info("Saved job output result to: {}", outputJsonPath);
    } catch (Exception e) {
      logger.error("Failed to save output result to file", e);
    }
  }
}
