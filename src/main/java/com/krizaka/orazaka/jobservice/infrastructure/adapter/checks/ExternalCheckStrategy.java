package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import com.krizaka.orazaka.core.application.checks.ExternalCheckService;
import com.krizaka.orazaka.core.domain.model.checks.CheckCall;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import com.krizaka.orazaka.core.domain.model.checks.CheckStepResult;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckAuditLog;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckKeyStore;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckSubjectPepper;
import com.krizaka.orazaka.core.domain.ports.outbound.ExternalCheckConnector;
import com.krizaka.orazaka.core.infrastructure.support.CoreException;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the engine's external checks as job steps (orazaka-packs#138): the capabilities {@code
 * orazaka.core.checks.courtrecords}, {@code .credit} and {@code .identity}, whose rows all name the
 * handler {@code check.external}. The purpose is read from the feature key by the engine ({@link
 * CheckPurpose#ofFeatureKey}), so this executor never branches on a key.
 *
 * <p>Every job goes through the engine's one door, {@link ExternalCheckService}: consent, key,
 * hold, provider, audit, in that order. The door is built for each job because two of its
 * collaborators are the job's own: the consents are the ones the job lists ({@link JobConsents}),
 * and the meter is the job's hold ({@link JobHoldMeter}). The connector, the keys, the audit, the
 * pepper and the clock are shared.
 *
 * <p>The outputs are the step contract of {@code orazaka-packs}' {@code
 * tests/contracts/orazaka-core-checks.json}: {@code outcome}, {@code refusal}, {@code category},
 * {@code providerRef}, {@code calls}, and {@code resultJson}, the same object as a JSON string. The
 * consumption is {@code {calls: 0|1}}: one when the provider answered, none otherwise, so the
 * listener settles one {@code CALL} or releases the hold (orazaka-job-service#68).
 *
 * <p>Fails closed. An input that does not match the contract is refused as {@code INPUT_INVALID}
 * before anything is asked; no sealed subject pepper stops every check (the engine refuses to name
 * a subject without one). Neither message carries a value of the payload.
 */
public final class ExternalCheckStrategy implements JobExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ExternalCheckStrategy.class);

  /** Writes {@code resultJson}; nulls are kept, so {@code refusal: null} is in the text. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ExternalCheckConnector connector;
  private final CheckKeyStore keys;
  private final CheckAuditLog audit;
  private final CheckSubjectPepper pepper;
  private final Clock clock;

  /**
   * @param connector the provider
   * @param keys the provider keys
   * @param audit where the audit lines go
   * @param pepper the sealed pepper of the audit's subject reference; empty stops every check
   * @param clock the clock
   */
  public ExternalCheckStrategy(
      ExternalCheckConnector connector,
      CheckKeyStore keys,
      CheckAuditLog audit,
      CheckSubjectPepper pepper,
      Clock clock) {
    this.connector = Objects.requireNonNull(connector, "connector must not be null");
    this.keys = Objects.requireNonNull(keys, "keys must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.pepper = Objects.requireNonNull(pepper, "pepper must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public String handlerKey() {
    return "check.external";
  }

  @Override
  public JobExecutionResult execute(JobCommand command, JobExecutionContext context)
      throws JobExecutionException {
    if (CheckPurpose.ofFeatureKey(command.featureKey()).isEmpty()) {
      // A row pointing another capability at this handler is a registry defect, not the input's.
      throw new JobExecutionException("not an external check: " + command.featureKey());
    }
    CheckStepInput input = CheckStepInput.parse(command.payload());
    ExternalCheckService door =
        new ExternalCheckService(
            connector,
            new JobConsents(input.subject().subjectId(), input.consented()),
            keys,
            audit,
            JobHoldMeter.of(command),
            pepper,
            clock);
    CheckStepResult step;
    try {
      step =
          door.run(
              command.featureKey(),
              new CheckCall(context.actorId(), runIdOf(command), input.subject()));
    } catch (CoreException off) {
      // No sealed pepper: the engine will not name a subject, so no check runs.
      throw new JobExecutionException("external checks are off: " + off.getMessage(), off);
    }
    Map<String, Object> outputs = outputsOf(step);
    logger.info(
        "External check job {} ({}): {} with {} call(s)",
        command.jobId(),
        command.featureKey(),
        step.result().outcome(),
        step.calls());
    return JobExecutionResult.measured(
        outputs, Map.of("calls", step.calls()), connector.provider());
  }

  /**
   * The step contract's outputs. {@code resultJson} holds all five fields, {@code null} included;
   * the job's output map cannot hold a {@code null} (it is copied as an immutable map), so an
   * absent {@code refusal} or {@code providerRef} is left out of it, and a step reading it reads
   * nothing.
   */
  private static Map<String, Object> outputsOf(CheckStepResult step) {
    CheckResult result = step.result();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("outcome", result.outcome().name());
    fields.put("refusal", result.refusal() == null ? null : result.refusal().name());
    fields.put("category", result.category().name());
    fields.put("providerRef", result.providerRef());
    fields.put("calls", step.calls());
    Map<String, Object> outputs = new LinkedHashMap<>();
    fields.forEach(
        (name, value) -> {
          if (value != null) {
            outputs.put(name, value);
          }
        });
    outputs.put("resultJson", JSON.writeValueAsString(fields));
    return outputs;
  }

  /** The Studio run the step serves, or {@code null} for a job of no run. */
  private static UUID runIdOf(JobCommand command) throws JobExecutionException {
    Object runId = command.payload().get("runId");
    if (runId == null) {
      return null;
    }
    try {
      return UUID.fromString(runId.toString());
    } catch (IllegalArgumentException notAUuid) {
      // The producer's field, not the user's: a platform defect.
      throw new JobExecutionException("runId is not a UUID");
    }
  }
}
