package com.orazaka.jobservice.infrastructure.adapter.schedule;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.orazaka.jobservice.application.service.JobRetentionService;
import com.orazaka.jobservice.infrastructure.support.PathResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class JobRetentionSweeperTest {

  @TempDir Path uploads;

  @Test
  @DisplayName("sweeps the root the executor writes to: purge first, then what came back")
  void sweepsTheExecutorsRoot() {
    JobRetentionService retention = mock(JobRetentionService.class);

    new JobRetentionSweeper(retention, uploads.toString()).sweep();

    Path root = PathResolver.resolve(uploads.toString());
    InOrder order = inOrder(retention);
    order.verify(retention).purgeExpired(root);
    order.verify(retention).reconcileOrphans(root);
  }
}
