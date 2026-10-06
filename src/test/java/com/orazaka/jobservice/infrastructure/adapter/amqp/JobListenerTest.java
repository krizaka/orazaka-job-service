package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.identity.domain.model.User;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import com.orazaka.jobservice.application.service.ContextService;
import com.orazaka.jobservice.application.service.UserDirectoryService;
import com.orazaka.jobservice.infrastructure.config.JobsProperties;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.orazaka.persistence.domain.ports.inbound.MessageDedupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JobListenerTest {

  @TempDir Path tempDir;

  private final com.orazaka.assets.application.service.EncryptedAssetService assets = testAssets();

  @Mock private JobPersistenceProvider jobPersistenceProvider;

  @Mock private JobEventPublisher jobEventPublisher;

  @Mock private ChatGenerationStrategy chatStrategy;

  @Mock private UserDirectoryService userDirectoryService;

  @Mock private ContextService contextService;

  @Mock private CapabilityManager capabilityManager;

  @Mock private MessageDedupService messageDedupService;

  /** What a free turn is recorded into; captured so a test can assert one was written. */
  private final java.util.List<com.orazaka.billing.domain.model.UnmeteredTurn> recordedTurns =
      new java.util.ArrayList<>();

  private final com.orazaka.billing.domain.port.UnmeteredTurnRepository unmeteredTurns =
      recordedTurns::add;

  private JobListener listener;
  private ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    // A claim succeeds unless a test says otherwise: Mockito's default `false` means every
    // message is a duplicate, which would silently turn every test here into a skip test.
    lenient().when(messageDedupService.claim(any(), any())).thenReturn(true);
    // Set up direct executor that runs task synchronously
    ExecutorService directExecutor = mock(ExecutorService.class);
    lenient()
        .doAnswer(
            invocation -> {
              Runnable runnable = invocation.getArgument(0);
              runnable.run();
              return null;
            })
        .when(directExecutor)
        .execute(any(Runnable.class));

    // DB-driven dispatch: every feature key resolves to a capability whose handler_key routes to
    // the (only) registered strategy under test.
    lenient().when(chatStrategy.handlerKey()).thenReturn("text.generate");
    // The listener now narrows the engine context onto the Tier-1 shape the executor port takes
    // (ADR-038), so it dereferences what ContextService returns. Production always resolves one;
    // the mock has to as well.
    lenient()
        .when(contextService.resolve(any(), any()))
        .thenAnswer(
            invocation ->
                new com.orazaka.core.domain.model.Context(
                    "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of()));
    lenient()
        .when(capabilityManager.findByFeatureKey(anyString()))
        .thenReturn(
            Optional.of(
                new CapabilityDeclaration(
                    "orazaka.core.chat.text",
                    "text.generate",
                    "job.text.process",
                    "KILOTOKEN",
                    "CHAT",
                    "BATCH",
                    "{}",
                    "{}",
                    true)));

    listener =
        new JobListener(
            jobPersistenceProvider,
            jobEventPublisher,
            List.of(chatStrategy),
            tempDir.toString(),
            directExecutor,
            new JobsProperties(5), // timeout seconds
            objectMapper,
            Optional.empty(),
            userDirectoryService,
            contextService,
            capabilityManager,
            messageDedupService,
            assets,
            unmeteredTurns);
  }

  @Test
  void onMessage_successfulExecution_completesJob() throws Exception {
    String jobId = "job-123";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    User user =
        new User(
            UUID.fromString(userId),
            "testuser",
            "test@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of());

    when(userDirectoryService.getUser(userId)).thenReturn(user);

    Map<String, Object> expectedResult = Map.of("content", "Response text");
    when(chatStrategy.execute(eq(msg), any())).thenReturn(JobExecutionResult.of(expectedResult));

    // Call listener
    listener.onMessage(msg, null);

    // Verify status updates
    verify(jobPersistenceProvider).updateJobStatus(jobId, "PROCESSING", null, null);
    verify(jobPersistenceProvider).updateJobStatus(jobId, "COMPLETED", expectedResult, null);

    // Check that files were written
    Path inputJson = tempDir.resolve(userId).resolve(jobId).resolve("input").resolve("input.json");
    Path resultJson =
        tempDir.resolve(userId).resolve(jobId).resolve("output").resolve("result.json");

    assertTrue(Files.exists(inputJson));
    assertTrue(Files.exists(resultJson));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aReportedGpuSeconds_cannotReplaceTheListenersOwnMeasurement() throws Exception {
    // ADR-064. The typed channel wins inside the measurement vocabulary and nowhere else: an
    // executor — possibly an out-of-tree jar (ADR-038) — reporting gpuSeconds: 0 used to replace
    // the occupancy this listener measured, which the GPU_SECOND rate and the margin are priced on.
    String jobId = "job-gpu";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser(userId))
        .thenReturn(
            new User(
                UUID.fromString(userId),
                "u",
                "u@example.com",
                true,
                Set.of("ROLE_USER"),
                Map.of()));
    when(chatStrategy.execute(eq(msg), any()))
        .thenReturn(
            JobExecutionResult.measured(
                Map.of("content", "ok"),
                Map.of("gpuSeconds", 0, "tokens", 42, "invented", 7),
                "llama"));

    listener.onMessage(msg, null);

    org.mockito.ArgumentCaptor<Map<String, Object>> consumption =
        org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(jobEventPublisher).emitDone(eq(jobId), any(), eq("llama"), any(), consumption.capture());
    assertEquals(
        42, consumption.getValue().get("tokens"), "a measurement in the vocabulary crosses");
    assertFalse(consumption.getValue().containsKey("invented"), "a key outside it does not");
    assertTrue(
        consumption.getValue().get("gpuSeconds") instanceof java.math.BigDecimal,
        "gpuSeconds is the listener's measurement, not the executor's: "
            + consumption.getValue().get("gpuSeconds"));
  }

  @Test
  void onMessage_strategyThrowsException_failsJob() throws Exception {
    String jobId = "job-123";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    User user =
        new User(
            UUID.fromString(userId),
            "testuser",
            "test@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of());

    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(eq(msg), any()))
        .thenThrow(new JobExecutionException("Strategy failed"));

    // Call listener
    listener.onMessage(msg, null);

    // Verify status updates
    verify(jobPersistenceProvider).updateJobStatus(jobId, "PROCESSING", null, null);
    verify(jobPersistenceProvider)
        .updateJobStatus(
            jobId,
            "FAILED",
            null,
            "com.orazaka.jobs.domain.exception.JobExecutionException: Strategy failed");
  }

  @Test
  void onMessage_unsupportedFeature_failsJob() {
    String jobId = "job-123";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "unsupported.feature",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    User user =
        new User(
            UUID.fromString(userId),
            "testuser",
            "test@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of());

    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(capabilityManager.findByFeatureKey("unsupported.feature")).thenReturn(Optional.empty());

    // Call listener
    listener.onMessage(msg, null);

    // Verify status updates
    verify(jobPersistenceProvider).updateJobStatus(jobId, "PROCESSING", null, null);
    verify(jobPersistenceProvider)
        .updateJobStatus(
            jobId,
            "FAILED",
            null,
            "com.orazaka.jobs.domain.exception.JobExecutionException: Unknown capability for feature key: unsupported.feature");
  }

  @Test
  void onMessage_duplicateMessageId_isSkipped() {
    when(messageDedupService.claim("job.executor", "m-dup")).thenReturn(false);
    JobCommand msg =
        new JobCommand(
            "job-dup",
            "user-1",
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    listener.onMessage(msg, "m-dup");

    verify(jobPersistenceProvider, never()).updateJobStatus(any(), any(), any(), any());
  }

  @Test
  void onMessage_marksMessageProcessedAfterTerminalOutcome() {
    JobCommand msg =
        new JobCommand(
            "job-dedup",
            "user-x",
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser("user-x")).thenThrow(new RuntimeException("boom"));

    listener.onMessage(msg, "m-9");

    verify(messageDedupService).claim("job.executor", "m-9");
  }

  @Test
  void onDeadLetter_carriesTheReservationBack_soTheHoldIsReleasedNotStranded() {
    JobCommand msg =
        new JobCommand(
            "job-held",
            "user-456",
            "feature-x",
            "default",
            Map.of("holdId", "hold-42", "correlationId", "job-held"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    listener.onDeadLetter(msg);

    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq("job-held"),
            org.mockito.ArgumentMatchers.eq("hold-42"),
            org.mockito.ArgumentMatchers.any(com.orazaka.jobs.domain.model.FailureCause.class),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void onDeadLetter_updatesStatusAndBroadcastsFailure() {
    JobCommand msg =
        new JobCommand(
            "job-123",
            "user-456",
            "feature-x",
            "default",
            Map.of(),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    listener.onDeadLetter(msg);

    verify(jobPersistenceProvider)
        .updateJobStatus(
            "job-123",
            "FAILED",
            null,
            "Job dead-lettered due to processing failure after max retry attempts");
    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq("job-123"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(com.orazaka.jobs.domain.model.FailureCause.class),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void onMessage_timeoutException_marksJobAsFailed() throws Exception {
    String jobId = "job-timeout";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    java.util.concurrent.ExecutorService threadExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      JobListener timeoutListener =
          new JobListener(
              jobPersistenceProvider,
              jobEventPublisher,
              List.of(chatStrategy),
              tempDir.toString(),
              threadExecutor,
              new JobsProperties(1), // 1 second timeout
              objectMapper,
              Optional.empty(),
              userDirectoryService,
              contextService,
              capabilityManager,
              messageDedupService,
              assets,
              unmeteredTurns);

      when(userDirectoryService.getUser(userId))
          .thenAnswer(
              invocation -> {
                Thread.sleep(2000);
                return null;
              });

      timeoutListener.onMessage(msg, null);

      verify(jobPersistenceProvider).updateJobStatus(jobId, "PROCESSING", null, null);
      verify(jobPersistenceProvider)
          .updateJobStatus(jobId, "FAILED", null, "EXECUTION_TIMEOUT_EXCEEDED");
    } finally {
      threadExecutor.shutdownNow();
    }
  }

  @Test
  void onMessage_interruptedException_marksJobAsFailed() throws Exception {
    String jobId = "job-interrupt";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "Hello test"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    java.util.concurrent.ExecutorService threadExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      JobListener interruptListener =
          new JobListener(
              jobPersistenceProvider,
              jobEventPublisher,
              List.of(chatStrategy),
              tempDir.toString(),
              threadExecutor,
              new JobsProperties(5), // timeout seconds
              objectMapper,
              Optional.empty(),
              userDirectoryService,
              contextService,
              capabilityManager,
              messageDedupService,
              assets,
              unmeteredTurns);

      lenient()
          .when(userDirectoryService.getUser(userId))
          .thenAnswer(
              invocation -> {
                Thread.sleep(10000);
                return null;
              });

      // Set thread status to interrupted before calling get()
      Thread.currentThread().interrupt();

      interruptListener.onMessage(msg, null);

      // Clear interrupted status so JUnit can proceed
      Thread.interrupted();

      verify(jobPersistenceProvider).updateJobStatus(jobId, "PROCESSING", null, null);
      verify(jobPersistenceProvider)
          .updateJobStatus(jobId, "FAILED", null, "EXECUTION_INTERRUPTED");
    } finally {
      threadExecutor.shutdownNow();
    }
  }

  /**
   * The archive of an input, now that the input arrives as an id.
   *
   * <p>This was {@code onMessage_withFilePath_copiesFileToInputDirectory}, which put a path in the
   * payload and asserted the listener copied whatever it named. That is the behaviour audit #32
   * closed: the path was the caller's, and on {@code POST /api/v1/jobs} the caller is an end user.
   * What is archived is the asset resolved against the job's actor (ADR-066).
   */
  @Test
  void onMessage_archivesTheResolvedAsset() throws Exception {
    String jobId = "job-file-copy";
    String userId = UUID.randomUUID().toString();
    String assetId = "b1b2c3d4-0000-0000-0000-00000000000a";
    Path owned = tempDir.resolve(userId).resolve("temp").resolve(assetId + ".mp4");
    Files.createDirectories(owned.getParent());
    Files.writeString(owned, "video content");

    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.video",
            "default",
            Map.of("prompt", "Hello test", "assetId", assetId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    User user =
        new User(
            UUID.fromString(userId),
            "testuser",
            "test@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of());

    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("result", "done")));

    listener.onMessage(msg, null);

    Path copiedFile = tempDir.resolve(userId).resolve(jobId).resolve("input").resolve("input.mp4");
    assertTrue(Files.exists(copiedFile));
    // The archived copy is sealed like everything else in the store (ADR-054), so it is read back
    // through it. Reading the raw file here would assert the archive is still in the clear.
    assertEquals(
        "video content",
        new String(
            assets.readAllBytes(copiedFile, false), java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * The archive's name still follows the bytes, not the capability [PACK-003].
   *
   * <p>Was {@code onMessage_withAudioAndVisionFilePaths_copiesWithCorrectNames}, over payload
   * paths; the naming rule it guards is unchanged and the inputs are now ids (ADR-066).
   */
  @Test
  void onMessage_namesEachArchivedAssetFromItsType() throws Exception {
    String jobId = "job-file-copy-media";
    String userId = UUID.randomUUID().toString();
    Path temp = tempDir.resolve(userId).resolve("temp");
    Files.createDirectories(temp);
    String audioId = "b1b2c3d4-0000-0000-0000-00000000000b";
    String visionId = "b1b2c3d4-0000-0000-0000-00000000000c";
    String otherId = "b1b2c3d4-0000-0000-0000-00000000000d";
    Files.writeString(temp.resolve(audioId + ".mp3"), "audio");
    Files.writeString(temp.resolve(visionId + ".png"), "image");
    Files.writeString(temp.resolve(otherId + ".txt"), "text");

    User user =
        new User(
            UUID.fromString(userId),
            "testuser",
            "test@example.com",
            true,
            Set.of("ROLE_USER"),
            Map.of());

    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("result", "done")));

    listener.onMessage(
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.audio",
            "default",
            Map.of("assetId", audioId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);
    assertTrue(
        Files.exists(tempDir.resolve(userId).resolve(jobId).resolve("input").resolve("input.mp3")));

    listener.onMessage(
        new JobCommand(
            jobId + "-v",
            userId,
            "orazaka.core.vision",
            "default",
            Map.of("assetId", visionId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);
    assertTrue(
        Files.exists(
            tempDir.resolve(userId).resolve(jobId + "-v").resolve("input").resolve("input.png")));

    listener.onMessage(
        new JobCommand(
            jobId + "-o",
            userId,
            "orazaka.core.other",
            "default",
            Map.of("assetId", otherId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);
    assertTrue(
        Files.exists(
            tempDir.resolve(userId).resolve(jobId + "-o").resolve("input").resolve("input.txt")));
  }

  @Test
  void onMessage_refusal_recordsThePacksOwnSentence_notTheExceptionClassName() {
    String jobId = "job-refused";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "conseil juridique ?"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);

    when(userDirectoryService.getUser(userId))
        .thenThrow(
            new com.orazaka.core.application.pipeline.PipelineShortCircuitException(
                "ScopeGuardInterceptor",
                "out_of_declared_scope",
                "Je rédige et relis des documents. Je ne donne pas de conseil juridique.",
                null));

    listener.onMessage(msg, null);

    // A refusal is an answer the pack wrote, not a stack trace: the user must read it verbatim.
    verify(jobPersistenceProvider)
        .updateJobStatus(
            jobId,
            "FAILED",
            null,
            "Je rédige et relis des documents. Je ne donne pas de conseil juridique.");
  }

  @Test
  void refusal_isDeclaredAsAGuardRefusal_notLeftForTheSagaToReadOffTheMessage() {
    String jobId = "job-refused-cause";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser(userId))
        .thenThrow(
            new com.orazaka.core.application.pipeline.PipelineShortCircuitException(
                "ScopeGuardInterceptor",
                "out_of_declared_scope",
                "Je ne réponds pas à cela.",
                null));

    listener.onMessage(msg, null);

    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(
                com.orazaka.jobs.domain.model.FailureCause.GUARD_REFUSAL),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void anUnanticipatedFailure_isAnExecutorFault_neverInputInvalid() {
    // ADR-046 §2's counter-example, pinned: an exception that happened while user data was in
    // scope must not be read as the user's fault. Only a validator that DECLARED the payload
    // unusable may say INPUT_INVALID, and this branch never does.
    String jobId = "job-our-bug";
    JobCommand msg =
        new JobCommand(
            jobId,
            "user-x",
            "orazaka.core.chat.text",
            "default",
            Map.of(),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser("user-x"))
        .thenThrow(new IllegalStateException("compose requires at least one readable photo"));

    listener.onMessage(msg, null);

    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(
                com.orazaka.jobs.domain.model.FailureCause.EXECUTOR_FAULT),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void anUnreachableDependency_isPlatformUnavailable() {
    String jobId = "job-ollama-down";
    JobCommand msg =
        new JobCommand(
            jobId,
            "user-y",
            "orazaka.core.chat.text",
            "default",
            Map.of(),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser("user-y"))
        .thenThrow(
            new RuntimeException("wrapped", new java.net.ConnectException("Connection refused")));

    listener.onMessage(msg, null);

    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(
                com.orazaka.jobs.domain.model.FailureCause.PLATFORM_UNAVAILABLE),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void aDisabledEngine_isPlatformUnavailable_notAGateRefusalThatWouldBeBilled() {
    // ADR-062. A PipelineShortCircuitException here would be GUARD_REFUSAL, and the saga settles
    // the
    // work a run measured before a guard refused. The operator switching the engine off is not
    // that.
    String jobId = "job-engine-off";
    JobCommand msg =
        new JobCommand(
            jobId,
            "user-z",
            "orazaka.core.chat.text",
            "default",
            Map.of(),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    when(userDirectoryService.getUser("user-z"))
        .thenThrow(
            new RuntimeException(
                "wrapped", new com.orazaka.core.application.pipeline.PipelineDisabledException()));

    listener.onMessage(msg, null);

    verify(jobEventPublisher)
        .emitError(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(
                com.orazaka.jobs.domain.model.FailureCause.PLATFORM_UNAVAILABLE),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void aJobThatDeclaresNoClass_isRefusedBeforeAnythingIsStored() throws Exception {
    // ADR-065. Read as STANDARD, a SENSITIVE step whose producer forgot to declare would be kept
    // indefinitely by the plane whose retention the class decides.
    String jobId = "job-undeclared";
    String userId = UUID.randomUUID().toString();
    JobCommand msg =
        new JobCommand(jobId, userId, "orazaka.core.chat.text", Map.of("prompt", "mes mots"));
    when(jobPersistenceProvider.getJob(jobId)).thenReturn(Optional.empty());

    listener.onMessage(msg, "m-undeclared");

    verify(jobPersistenceProvider, never()).createJob(any(), any(), any(), any(), any());
    verify(jobPersistenceProvider, never()).updateJobStatus(any(), any(), any(), any());
    verify(chatStrategy, never()).execute(any(), any());
    assertFalse(Files.exists(tempDir.resolve(userId).resolve(jobId)), "no input was archived");
    verify(jobEventPublisher)
        .emitError(
            jobId,
            null,
            com.orazaka.jobs.domain.model.FailureCause.EXECUTOR_FAULT,
            "JOB_DATA_CLASS_UNDECLARED");
  }

  @Test
  void aPathInThePayloadIsNotAnAssetReference() throws Exception {
    // ADR-066, audit #32. `POST /api/v1/jobs` passes its payload through verbatim, so the path is
    // whatever an authenticated caller typed — here, another actor's upload.
    String jobId = "job-path";
    String userId = UUID.randomUUID().toString();
    String stranger = UUID.randomUUID().toString();
    java.nio.file.Path theirs = tempDir.resolve(stranger).resolve("temp").resolve("secret.png");
    Files.createDirectories(theirs.getParent());
    Files.writeString(theirs, "not yours");

    JobCommand msg =
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of(
                "prompt",
                "x",
                "filePath",
                theirs.toAbsolutePath().toString(),
                "imagePath",
                theirs.toAbsolutePath().toString()),
            com.orazaka.jobs.domain.model.DataClass.STANDARD);
    User user =
        new User(
            UUID.fromString(userId), "u", "u@example.com", true, Set.of("ROLE_USER"), Map.of());
    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("content", "ok")));

    listener.onMessage(msg, null);

    org.mockito.ArgumentCaptor<JobCommand> executed =
        org.mockito.ArgumentCaptor.forClass(JobCommand.class);
    verify(chatStrategy).execute(executed.capture(), any());
    assertNull(executed.getValue().filePath(), "the payload's filePath");
    assertNull(executed.getValue().payload().get("imagePath"), "the payload's imagePath");
    assertFalse(
        Files.exists(tempDir.resolve(userId).resolve(jobId).resolve("input").resolve("secret.png")),
        "another actor's file was not archived into this job");
  }

  @Test
  void anAssetIdResolvesOnlyAgainstItsOwnJob() throws Exception {
    String jobId = "job-asset";
    String userId = UUID.randomUUID().toString();
    String assetId = "a1b2c3d4-0000-0000-0000-000000000001";
    java.nio.file.Path owned = tempDir.resolve(userId).resolve("temp").resolve(assetId + ".png");
    Files.createDirectories(owned.getParent());
    Files.writeString(owned, "mine");

    User user =
        new User(
            UUID.fromString(userId), "u", "u@example.com", true, Set.of("ROLE_USER"), Map.of());
    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("content", "ok")));

    listener.onMessage(
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x", "assetId", assetId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);

    org.mockito.ArgumentCaptor<JobCommand> executed =
        org.mockito.ArgumentCaptor.forClass(JobCommand.class);
    verify(chatStrategy).execute(executed.capture(), any());
    assertEquals(owned.toAbsolutePath().toString(), executed.getValue().filePath());
  }

  @Test
  void anotherActorsAssetIdResolvesToNothing() throws Exception {
    String assetId = "a1b2c3d4-0000-0000-0000-000000000002";
    String stranger = UUID.randomUUID().toString();
    java.nio.file.Path theirs = tempDir.resolve(stranger).resolve("temp").resolve(assetId + ".png");
    Files.createDirectories(theirs.getParent());
    Files.writeString(theirs, "not yours");
    String userId = UUID.randomUUID().toString();

    User user =
        new User(
            UUID.fromString(userId), "u", "u@example.com", true, Set.of("ROLE_USER"), Map.of());
    when(userDirectoryService.getUser(userId)).thenReturn(user);
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("content", "ok")));

    listener.onMessage(
        new JobCommand(
            "job-stranger",
            userId,
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x", "assetId", assetId),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);

    org.mockito.ArgumentCaptor<JobCommand> executed =
        org.mockito.ArgumentCaptor.forClass(JobCommand.class);
    verify(chatStrategy).execute(executed.capture(), any());
    // Not found and not yours are one answer: the step fails on the missing file.
    assertNull(executed.getValue().filePath());
  }

  @Test
  void aCompletedJobWithNoMeasurementRecordsAnUnmeteredTurn() throws Exception {
    // audit #46: a provider that refuses verbose_json is retried plain, the job settles unmeasured
    // and the hold is released — free inference that left no trace. UnmeteredTurn is what M1.7
    // wrote for exactly this question (ADR-064), so it is what records it.
    String jobId = "job-unmetered";
    String userId = UUID.randomUUID().toString();
    when(capabilityManager.findByFeatureKey(anyString()))
        .thenReturn(
            Optional.of(
                new CapabilityDeclaration(
                    "orazaka.core.media.audio.analysis",
                    "text.generate",
                    "job.media.generate",
                    "AUDIO_MINUTE",
                    "AUDIO",
                    "BATCH",
                    "{}",
                    "{}",
                    true)));
    when(userDirectoryService.getUser(userId))
        .thenReturn(
            new User(
                UUID.fromString(userId),
                "u",
                "u@example.com",
                true,
                Set.of("ROLE_USER"),
                Map.of()));
    // The executor measured nothing the unit can price: gpuSeconds is always reported, and
    // AUDIO_MINUTE cannot be derived from it.
    when(chatStrategy.execute(any(), any()))
        .thenReturn(JobExecutionResult.of(Map.of("analysis", "x")));

    listener.onMessage(
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.media.audio.analysis",
            "whisper-base",
            Map.of("prompt", "x", "holdId", "hold-46", "correlationId", "corr-46"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);

    assertEquals(1, recordedTurns.size(), "a free turn must be recorded, not shrugged off");
    assertEquals("corr-46", recordedTurns.get(0).correlationId());
    assertEquals(userId, recordedTurns.get(0).actorId());
    assertTrue(recordedTurns.get(0).reason().contains("AUDIO_MINUTE"));
  }

  @Test
  void aMeasuredJobRecordsNothing() throws Exception {
    String jobId = "job-measured";
    String userId = UUID.randomUUID().toString();
    when(capabilityManager.findByFeatureKey(anyString()))
        .thenReturn(
            Optional.of(
                new CapabilityDeclaration(
                    "orazaka.core.media.audio.analysis",
                    "text.generate",
                    "job.media.generate",
                    "AUDIO_MINUTE",
                    "AUDIO",
                    "BATCH",
                    "{}",
                    "{}",
                    true)));
    when(userDirectoryService.getUser(userId))
        .thenReturn(
            new User(
                UUID.fromString(userId),
                "u",
                "u@example.com",
                true,
                Set.of("ROLE_USER"),
                Map.of()));
    when(chatStrategy.execute(any(), any()))
        .thenReturn(
            JobExecutionResult.measured(
                Map.of("analysis", "x"),
                Map.of("audioSeconds", new java.math.BigDecimal("132.5")),
                "whisper-base"));

    listener.onMessage(
        new JobCommand(
            jobId,
            userId,
            "orazaka.core.media.audio.analysis",
            "whisper-base",
            Map.of("prompt", "x", "holdId", "hold-46"),
            com.orazaka.jobs.domain.model.DataClass.STANDARD),
        null);

    assertTrue(recordedTurns.isEmpty(), "a measured job is billed, not reconciled");
  }

  @Test
  void theClassAStudioPublishesByName_isReadBackAsTheClass() {
    // The studio publishes a map, not this record (AmqpStepExecutionAdapter): the class crosses the
    // broker as its name and has to arrive as the enum, or every run step is refused as undeclared.
    String published =
        "{\"jobId\":\"j\",\"userId\":\"u\",\"featureKey\":\"f\",\"model\":\"default\","
            + "\"payload\":{},\"dataClass\":\"SENSITIVE\"}";
    String undeclared =
        "{\"jobId\":\"j\",\"userId\":\"u\",\"featureKey\":\"f\",\"model\":\"default\","
            + "\"payload\":{}}";

    assertEquals(
        com.orazaka.jobs.domain.model.DataClass.SENSITIVE,
        objectMapper.readValue(published, JobCommand.class).dataClass());
    assertNull(objectMapper.readValue(undeclared, JobCommand.class).dataClass());
  }

  @Test
  void aRowTheProducerAlreadyCreated_isClosedWhenItsCommandDeclaresNoClass() {
    String jobId = "job-undeclared-row";
    JobCommand msg = new JobCommand(jobId, "user-u", "orazaka.core.chat.text", Map.of());
    when(jobPersistenceProvider.getJob(jobId))
        .thenReturn(
            Optional.of(
                new com.orazaka.persistence.domain.model.JobDto(
                    jobId,
                    "user-u",
                    "orazaka.core.chat.text",
                    "PENDING",
                    Map.of(),
                    null,
                    null,
                    java.time.Instant.now(),
                    java.time.Instant.now())));

    listener.onMessage(msg, null);

    verify(jobPersistenceProvider)
        .updateJobStatus(jobId, "FAILED", null, "JOB_DATA_CLASS_UNDECLARED");
    verify(jobPersistenceProvider, never()).updateJobStatus(jobId, "PROCESSING", null, null);
  }

  @Test
  void anAdoptedJob_keepsTheClassItsProducerDeclared() {
    String jobId = "job-adopted";
    JobCommand msg =
        new JobCommand(
            jobId,
            "user-a",
            "orazaka.core.chat.text",
            "default",
            Map.of("prompt", "x"),
            com.orazaka.jobs.domain.model.DataClass.SENSITIVE);
    when(jobPersistenceProvider.getJob(jobId)).thenReturn(Optional.empty());
    when(userDirectoryService.getUser("user-a")).thenThrow(new RuntimeException("boom"));

    listener.onMessage(msg, null);

    verify(jobPersistenceProvider)
        .createJob(
            jobId,
            "user-a",
            "orazaka.core.chat.text",
            Map.of("prompt", "x"),
            com.orazaka.jobs.domain.model.DataClass.SENSITIVE);
  }

  /** A real store over a throwaway keyring: tests exercise the format, never a stub of it. */
  static com.orazaka.assets.application.service.EncryptedAssetService testAssets() {
    try {
      java.nio.file.Path keyFile =
          java.nio.file.Files.createTempDirectory("orz-keys").resolve("master.key");
      com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider.addKey(keyFile, "test");
      return new com.orazaka.assets.application.service.EncryptedAssetService(
          new com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider(keyFile), 4096);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
