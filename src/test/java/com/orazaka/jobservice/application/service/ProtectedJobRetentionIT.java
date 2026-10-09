package com.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.messaging.dedup.MessageDedup;
import com.orazaka.assets.application.service.EncryptedAssetService;
import com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider;
import com.orazaka.core.domain.model.Context;
import com.orazaka.identity.domain.model.User;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.DataClass;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import com.orazaka.jobs.domain.port.JobExecutor;
import com.orazaka.jobservice.infrastructure.adapter.amqp.JobEventPublisher;
import com.orazaka.jobservice.infrastructure.adapter.amqp.JobListener;
import com.orazaka.jobservice.infrastructure.config.JobsProperties;
import com.orazaka.persistence.domain.model.JobDto;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.orazaka.test.architecture.SqlBoundaryRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * A protected job leaves nothing behind in the job plane once it is terminal (ADR-065, audit #27).
 *
 * <p>Against the <b>real</b> {@code infra/initdb/30-jobs-config.sql}, and through the real {@link
 * JobListener} writing real encrypted files at the real layout: the claim is about what the
 * executor keeps, so the executor has to be what kept it. Only the model call is stubbed, and the
 * job row is written by a JDBC twin of the port over the real table — the constraint under test is
 * the schema's, not JPA's.
 *
 * <p>The row and the files are asserted apart. A sweep that deleted the row and lost the files
 * would pass a test that only counted rows, and the files are the carrier that survives it.
 */
class ProtectedJobRetentionIT {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440065";
  private static final String FEATURE = "orazaka.core.chat.completion";
  private static final String USER_WORDS = "je n'arrive plus à dormir depuis la séparation";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("orazaka_db")
          .withUsername("postgres")
          .withPassword("postgres");

  /**
   * The job plane's own statements, read out of the real initdb file: the job table, its indexes
   * and the purge tombstones — exactly what this suite makes claims about.
   *
   * <p>Not the whole file, and the reason is a finding rather than a preference: {@code
   * 30-jobs-config.sql} has not bootstrapped a fresh database since 2026-09-09 — {@code
   * orazaka_capabilities} misses a comma between two constraints and carries a trailing one, so
   * psql stops at that table. Recorded for M2's report, not repaired here (ADR-065 §7). Loading the
   * file whole would make this suite a test of that defect instead of retention.
   */
  private static final Pattern JOB_PLANE_DDL =
      Pattern.compile(
          "(?s)CREATE TABLE orazaka_jobs \\(.*?\\n\\);"
              + "|CREATE INDEX \\w+ ON orazaka_jobs[^;]*;"
              + "|CREATE TABLE orazaka_job_purge \\(.*?\\n\\);");

  private static JdbcTemplate jdbcTemplate;
  private static JobRetentionService retention;
  private static ExecutorService executor;

  @TempDir Path uploads;
  @TempDir Path keys;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    createJobPlaneTables();
    retention = new JobRetentionService(jdbcTemplate);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  private static void createJobPlaneTables() {
    String initDb;
    try {
      initDb =
          Files.readString(
              SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                  .resolve("30-jobs-config.sql"));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    Matcher statement = JOB_PLANE_DDL.matcher(initDb);
    int executed = 0;
    while (statement.find()) {
      jdbcTemplate.execute(statement.group());
      executed++;
    }
    // Two tables and two indexes. Fewer means the pattern stopped matching the file and this
    // suite would be running against whatever it did not create.
    assertThat(executed).as("job-plane statements read from the initdb file").isEqualTo(4);
  }

  @AfterAll
  static void stopContainer() {
    executor.shutdownNow();
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM orazaka_jobs");
    jdbcTemplate.update("DELETE FROM orazaka_job_purge");
  }

  @Test
  @DisplayName("a SENSITIVE step run to terminal leaves neither its row nor its files")
  void aSensitiveStepRunToTerminal_leavesNeitherItsRowNorItsFiles() throws IOException {
    JobListener listener = listener();
    String sensitive = UUID.randomUUID().toString();
    String standard = UUID.randomUUID().toString();
    listener.onMessage(command(sensitive, DataClass.SENSITIVE), null);
    listener.onMessage(command(standard, DataClass.STANDARD), null);

    // What there is to purge: the executor kept the user's words in the row and in two files.
    assertThat(status(sensitive)).as("the step ran to terminal").isEqualTo("COMPLETED");
    assertThat(Files.isRegularFile(jobDirectory(sensitive).resolve("input/input.json")))
        .as("the archived input")
        .isTrue();
    assertThat(Files.isRegularFile(jobDirectory(sensitive).resolve("output/result.json")))
        .as("the archived output")
        .isTrue();

    retention.purgeExpired(uploads);

    assertThat(rowCount(sensitive)).as("the SENSITIVE job's row").isZero();
    assertThat(Files.exists(jobDirectory(sensitive))).as("the SENSITIVE job's files").isFalse();
    assertThat(rowCount(standard)).as("the STANDARD job's row").isOne();
    assertThat(Files.exists(jobDirectory(standard))).as("the STANDARD job's files").isTrue();
  }

  @Test
  @DisplayName("a job still running keeps its material: the window opens at terminal")
  void aJobStillRunning_isNotPurged() {
    String running = UUID.randomUUID().toString();
    jdbcTemplate.update(
        "INSERT INTO orazaka_jobs (id, user_id, feature_key, status, data_class)"
            + " VALUES (?, ?, ?, 'PROCESSING', 'REGULATED')",
        running,
        ACTOR,
        FEATURE);

    retention.purgeExpired(uploads);

    assertThat(rowCount(running)).isOne();
  }

  @Test
  @DisplayName("the file goes before the row: a directory that cannot be deleted keeps its row")
  void aDirectoryThatCannotBeDeleted_keepsTheRowThatPointsAtIt() throws IOException {
    String jobId = UUID.randomUUID().toString();
    listener().onMessage(command(jobId, DataClass.SENSITIVE), null);
    Path output = jobDirectory(jobId).resolve("output");
    Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      retention.purgeExpired(uploads);
      assertThat(rowCount(jobId)).as("the only pointer to what could not be deleted").isOne();
    } finally {
      Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    retention.purgeExpired(uploads);

    assertThat(Files.exists(jobDirectory(jobId))).as("the files, on the next sweep").isFalse();
    assertThat(rowCount(jobId)).as("the row, on the next sweep").isZero();
  }

  @Test
  @DisplayName("a directory written after its job's purge is deleted again")
  void aDirectoryWrittenAfterThePurge_isDeletedAgain() throws IOException {
    String jobId = UUID.randomUUID().toString();
    EncryptedAssetService assets = assets();
    listener(assets).onMessage(command(jobId, DataClass.REGULATED), null);
    retention.purgeExpired(uploads);
    // An abandoned execution finishing late: JobListener does not cancel a timed-out one.
    assets.write(
        jobDirectory(jobId).resolve("output/result.json"), "{\"content\":\"tard\"}".getBytes());

    retention.reconcileOrphans(uploads);

    assertThat(Files.exists(jobDirectory(jobId))).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM orazaka_job_purge p WHERE job_id = ?",
                String.class,
                jobId))
        .as("the tombstone names the job, never what it held")
        .doesNotContain("dormir")
        .contains(jobId);
  }

  @Test
  @DisplayName("the schema refuses a job that declares no class")
  void theSchemaRefusesAJobWithNoClass() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO orazaka_jobs (id, user_id, feature_key, status)"
                        + " VALUES (?, ?, ?, 'PENDING')",
                    UUID.randomUUID().toString(),
                    ACTOR,
                    FEATURE))
        .hasMessageContaining("data_class");
  }

  private JobListener listener() {
    return listener(assets());
  }

  private JobListener listener(EncryptedAssetService assets) {
    JobExecutor model = mock(JobExecutor.class);
    when(model.handlerKey()).thenReturn("text.generate");
    try {
      lenient()
          .when(model.execute(any(), any()))
          .thenReturn(JobExecutionResult.of(Map.of("content", "Ce que vous décrivez compte.")));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    UserDirectoryService users = mock(UserDirectoryService.class);
    lenient()
        .when(users.getUser(anyString()))
        .thenReturn(
            new User(
                UUID.fromString(ACTOR),
                "retention",
                "retention@example.com",
                true,
                Set.of("ROLE_USER"),
                Map.of()));
    ContextService contexts = mock(ContextService.class);
    lenient()
        .when(contexts.resolve(any(), any()))
        .thenReturn(new Context(ACTOR, "run", Map.of(), Set.of()));
    CapabilityManager capabilities = mock(CapabilityManager.class);
    lenient()
        .when(capabilities.findByFeatureKey(anyString()))
        .thenReturn(
            Optional.of(
                new CapabilityDeclaration(
                    FEATURE,
                    "text.generate",
                    "job.text.process",
                    "KILOTOKEN",
                    "CHAT",
                    "BATCH",
                    "{}",
                    "{}",
                    true)));
    MessageDedup dedup = mock(MessageDedup.class);
    lenient().when(dedup.claim(any(), any())).thenReturn(true);
    return new JobListener(
        new JdbcJobs(jdbcTemplate),
        mock(JobEventPublisher.class),
        List.of(model),
        uploads.toString(),
        executor,
        new JobsProperties(30),
        new ObjectMapper(),
        Optional.empty(),
        users,
        contexts,
        capabilities,
        dedup,
        assets,
        turn -> {});
  }

  private EncryptedAssetService assets() {
    try {
      Path keyFile = keys.resolve("master.key");
      if (!Files.exists(keyFile)) {
        FileMasterKeyProvider.addKey(keyFile, "retention");
      }
      return new EncryptedAssetService(new FileMasterKeyProvider(keyFile), 4096);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static JobCommand command(String jobId, DataClass dataClass) {
    return new JobCommand(
        jobId,
        ACTOR,
        FEATURE,
        "default",
        Map.of("prompt", USER_WORDS, JobCommand.GUARD_SUBJECT_KEY, USER_WORDS),
        dataClass);
  }

  private Path jobDirectory(String jobId) {
    return uploads.resolve(ACTOR).resolve(jobId);
  }

  private static int rowCount(String jobId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM orazaka_jobs WHERE id = ?", Integer.class, jobId);
  }

  private static String status(String jobId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM orazaka_jobs WHERE id = ?", String.class, jobId);
  }

  /**
   * The job port over the real table, for the three calls the executor makes. The production
   * adapter is JPA and package-private to its module; what this suite proves is the schema and the
   * sweep, and the row it sweeps has the columns the DDL gives it either way.
   */
  private static final class JdbcJobs implements JobPersistenceProvider {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    JdbcJobs(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public String createJob(
        String userId, String featureKey, Map<String, Object> payload, DataClass dataClass) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String createJob(
        String jobId,
        String userId,
        String featureKey,
        Map<String, Object> payload,
        DataClass dataClass) {
      jdbc.update(
          "INSERT INTO orazaka_jobs (id, user_id, feature_key, status, payload, data_class)"
              + " VALUES (?, ?, ?, 'PENDING', ?::jsonb, ?)",
          jobId,
          userId,
          featureKey,
          json.writeValueAsString(payload),
          dataClass.name());
      return jobId;
    }

    @Override
    public void updateJobStatus(
        String jobId, String status, Map<String, Object> result, String errorMessage) {
      int updated =
          jdbc.update(
              "UPDATE orazaka_jobs SET status = ?, result = ?::jsonb, error_message = ?,"
                  + " updated_at = now() WHERE id = ?",
              status,
              result == null ? null : json.writeValueAsString(result),
              errorMessage,
              jobId);
      if (updated == 0) {
        throw new IllegalArgumentException("Job not found: " + jobId);
      }
    }

    @Override
    public Optional<JobDto> getJob(String jobId) {
      return jdbc
          .query(
              "SELECT id, user_id, feature_key, status FROM orazaka_jobs WHERE id = ?",
              (rs, rowNum) ->
                  new JobDto(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4),
                      Map.of(),
                      null,
                      null,
                      java.time.Instant.now(),
                      java.time.Instant.now()),
              jobId)
          .stream()
          .findFirst();
    }

    @Override
    public Page<JobDto> getJobsByUserId(String userId, Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Page<JobDto> getAllJobs(Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<JobDto> findJobsByStatuses(Collection<String> statuses) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void purgeJobsByUserId(String userId) {
      throw new UnsupportedOperationException();
    }
  }
}
