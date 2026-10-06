package com.orazaka.jobservice.infrastructure.adapter.schedule;

import com.orazaka.jobservice.application.service.JobRetentionService;
import com.orazaka.jobservice.infrastructure.support.PathResolver;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the job plane's retention on a fixed delay (ADR-065).
 *
 * <p>The upload root is resolved from the same key, by the same resolver, as {@code JobListener}
 * resolves the directory it writes to. A sweep that computed its own would purge a directory the
 * executor never wrote and report success.
 *
 * <p>The interval is bootstrap wiring and not a retention period: the window belongs to the class,
 * and this only says how soon after a window closes the sweep notices.
 */
@Component
public class JobRetentionSweeper {

  private final JobRetentionService jobRetentionService;
  private final Path uploadRoot;

  /**
   * @param jobRetentionService what purges
   * @param uploadDir the executor's upload root
   */
  public JobRetentionSweeper(
      JobRetentionService jobRetentionService,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir) {
    this.jobRetentionService =
        Objects.requireNonNull(jobRetentionService, "JobRetentionService cannot be null");
    this.uploadRoot = PathResolver.resolve(uploadDir);
  }

  /** Purges expired jobs, then deletes any purged job's directory that came back. */
  @Scheduled(fixedDelayString = "${orazaka.jobs.retention-sweep-interval:60000}")
  public void sweep() {
    jobRetentionService.purgeExpired(uploadRoot);
    jobRetentionService.reconcileOrphans(uploadRoot);
  }
}
