package com.krizaka.orazaka.jobservice.infrastructure.adapter.schedule;

import com.krizaka.orazaka.jobservice.application.service.WorkerRegistryService;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks workers that have stopped heartbeating as {@code STALE}.
 *
 * <p>Without this the registry only ever grows more optimistic: a worker that died stays {@code
 * HEALTHY} forever and phase F would read it as available, which is exactly the "user pays for a
 * run that was doomed before it started" failure the registry exists to prevent (design §3.5).
 *
 * <p>The horizon is bootstrap wiring, not domain data: it is a property of how often workers are
 * configured to heartbeat, which is a deployment fact, so it belongs in {@code application.yml} and
 * not in a table (AGENTS.md §4).
 */
@Component
public class WorkerHeartbeatSweeper {

  private final WorkerRegistryService workerRegistryService;
  private final Duration horizon;

  /**
   * @param workerRegistryService the registry to sweep
   * @param horizonSeconds how long silence is tolerated before a worker is presumed gone
   */
  public WorkerHeartbeatSweeper(
      WorkerRegistryService workerRegistryService,
      @Value("${orazaka.jobs.worker-heartbeat-horizon-seconds:90}") long horizonSeconds) {
    this.workerRegistryService =
        Objects.requireNonNull(workerRegistryService, "WorkerRegistryService cannot be null");
    this.horizon = Duration.ofSeconds(horizonSeconds);
  }

  /**
   * Sweeps on a fixed delay; the interval is bootstrap wiring for the same reason the horizon is.
   */
  @Scheduled(fixedDelayString = "${orazaka.jobs.worker-sweeper-interval:30000}")
  public void markStaleWorkers() {
    workerRegistryService.markStaleWorkers(horizon);
  }
}
