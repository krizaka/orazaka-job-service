package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckMeter;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import java.util.UUID;

/**
 * The {@code CALL} meter of a check that runs as a job: bound to the hold the job already carries,
 * it never takes a second one.
 *
 * <p><b>One hold per call.</b> A job reaching this executor was held upstream (ADR-033): either by
 * its own job hold ({@code holdId}), which billing settles from the job's {@code consumption}, or —
 * a Studio step, which never carries a hold (ADR-041) — by its run's hold, which the saga settles
 * once from the sum of its steps' consumption ({@code orazaka.metering.deferred}). Either way the
 * step's {@code calls} (1 when the provider answered, 0 otherwise) is what is charged, and {@link
 * com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp.JobListener JobListener} releases on 0
 * and settles one {@code CALL} on 1. A meter that held again here would hold the same call twice.
 * So {@link #hold} only checks that a hold covers the job, and {@link Hold#settle} and {@link
 * Hold#release} do nothing: the job's settlement is the one that counts.
 *
 * <p><b>Fail closed.</b> A job with neither a hold nor a run's deferred metering would make a paid
 * provider call that nothing bills. It is refused before the provider is asked, as a budget that
 * cannot hold the call ({@code BUDGET_EXHAUSTED}, {@code calls: 0}).
 */
final class JobHoldMeter implements CheckMeter {

  /** The job's own settlement settles or releases: nothing to do here. */
  private static final Hold THE_JOBS_HOLD =
      new Hold() {
        @Override
        public void settle() {
          // Settled by the job's consumption {calls: 1} (CallConsumption, ADR-033).
        }

        @Override
        public void release(String reason) {
          // Released by the job's consumption {calls: 0} (CallConsumption, ADR-033).
        }
      };

  private final boolean covered;

  private JobHoldMeter(boolean covered) {
    this.covered = covered;
  }

  /**
   * The meter of a job.
   *
   * @param command the job
   * @return a meter that holds when the job has a hold of its own or is metered by its run
   */
  static JobHoldMeter of(JobCommand command) {
    return new JobHoldMeter(command.holdId() != null || command.deferredMetering());
  }

  @Override
  public Hold hold(UUID runId, String actorId, String provider, CheckPurpose purpose) {
    if (!covered) {
      throw new BudgetExhaustedException(
          "no hold covers this job: a billable check never runs unmetered");
    }
    return THE_JOBS_HOLD;
  }
}
