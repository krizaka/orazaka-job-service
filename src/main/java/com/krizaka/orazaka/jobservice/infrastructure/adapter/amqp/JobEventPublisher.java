package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.jobs.domain.model.FailureCause;
import com.krizaka.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes job lifecycle events to the {@code orazaka.events} topic exchange (AGENTS.md §6: {@code
 * job.{jobId}.done|error}). Since the executor moved out of the router process (Phase 5), the SSE
 * relay can no longer observe the in-process job status change — so terminal outcomes are broadcast
 * as events, which the relay ({@code JobEventListener}) applies and fans out to the connected
 * clients. Mirrors the media worker's event contract (Phase 3b).
 *
 * <p>Terminal events also close the metering protocol (ADR-033 §6.3): they carry back the {@code
 * holdId} the submitting service reserved, so the billing consumer settles a completion and
 * releases a failure. A topic exchange fans out, so this is one payload serving both consumers —
 * the relay ignores the extra key it does not read.
 */
@Component
public class JobEventPublisher {

  private final RabbitTemplate rabbitTemplate;

  public JobEventPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate cannot be null");
  }

  /**
   * Emits a terminal success event carrying the job result, its reservation and what it consumed.
   *
   * @param jobId the completed job
   * @param holdId the reservation to settle, or {@code null} when the job was not metered
   * @param result the strategy's output
   * @param model the model that ran, so an aggregate settlement can price this step
   * @param consumption what the execution measured, settled against the hold's pinned rate
   */
  public void emitDone(
      String jobId,
      String holdId,
      String model,
      Map<String, Object> result,
      Map<String, Object> consumption) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("jobId", jobId);
    payload.put("result", result == null ? Map.of() : result);
    // WHAT RAN, never what it costs. An aggregate settlement prices each step against its own
    // (capability, model) row, and the capability is knowable from the feature key while the model
    // is only knowable here — so omitting it forced the orchestrator to price every step against
    // the run hold's own rate, which is the defect ADR-041 removes. A producer that has not chosen
    // a model sends the sentinel, and billing falls back to the capability's default row.
    if (model != null && !model.isBlank()) {
      payload.put("model", model);
    }
    putReservation(payload, holdId);
    if (consumption != null && !consumption.isEmpty()) {
      payload.put("consumption", consumption);
    }
    rabbitTemplate.convertAndSend(
        MessagingContract.EVENTS_EXCHANGE, "job." + jobId + ".done", payload);
  }

  /**
   * Emits a terminal failure event carrying the error reason, its typed cause and its reservation.
   *
   * <p>The cause is <b>declared</b>, never left for the consumer to read out of {@code error}
   * (ADR-053). This executor knows whether it refused a payload, lost its model or was stopped by a
   * gate; nothing downstream does, and everything downstream was guessing.
   *
   * @param jobId the failed job
   * @param holdId the reservation to release — a failed generation is never billed
   * @param cause why it failed, in the closed vocabulary of {@link FailureCause}
   * @param error the failure reason, for a human to read; the saga reads {@code cause} instead
   */
  public void emitError(String jobId, String holdId, FailureCause cause, String error) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("jobId", jobId);
    payload.put("error", error == null ? "Job failed" : error);
    payload.put("cause", (cause == null ? FailureCause.EXECUTOR_FAULT : cause).name());
    putReservation(payload, holdId);
    rabbitTemplate.convertAndSend(
        MessagingContract.EVENTS_EXCHANGE, "job." + jobId + ".error", payload);
  }

  /** Omits the key entirely when unmetered, so absence — not a null — means "never authorised". */
  private static void putReservation(Map<String, Object> payload, String holdId) {
    if (holdId != null && !holdId.isBlank()) {
      payload.put("holdId", holdId);
    }
  }
}
