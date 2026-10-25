package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckMeter;
import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One hold per call: the job's own, never a second one (ADR-033, ADR-041). */
class JobHoldMeterTest {

  private static JobCommand job(Map<String, Object> payload) {
    return new JobCommand(
        "job-1", "u", "orazaka.core.checks.credit", "default", payload, DataClass.STANDARD);
  }

  @Test
  @DisplayName("a job with its own hold: hold, settle and release take nothing more")
  void aHeldJob() {
    CheckMeter meter = JobHoldMeter.of(job(Map.of("holdId", "hold-1")));

    assertThatCode(
            () -> {
              CheckMeter.Hold hold = meter.hold(null, "u", "mock", CheckPurpose.CREDIT);
              hold.settle();
              hold.release("the provider did not answer: TIMEOUT");
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a Studio step, metered by its run's hold, is covered")
  void aRunMeteredStep() {
    CheckMeter meter = JobHoldMeter.of(job(Map.of(JobCommand.DEFERRED_METERING_KEY, true)));

    assertThatCode(() -> meter.hold(null, "u", "mock", CheckPurpose.CREDIT))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a job no hold covers is refused: a paid call never runs unmetered")
  void anUnheldJob() {
    CheckMeter meter = JobHoldMeter.of(job(Map.of()));

    assertThatThrownBy(() -> meter.hold(null, "u", "mock", CheckPurpose.CREDIT))
        .isInstanceOf(CheckMeter.BudgetExhaustedException.class);
  }
}
