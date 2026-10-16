package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunningJobsTest {

  @Test
  void cancel_interruptsTheThreadRunningTheJob() throws Exception {
    RunningJobs running = new RunningJobs();
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  running.bind("job-1");
                  started.countDown();
                  try {
                    Thread.sleep(Duration.ofSeconds(30));
                  } catch (InterruptedException e) {
                    interrupted.set(true);
                  } finally {
                    running.unbind("job-1");
                  }
                });
    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

    assertTrue(running.cancel("job-1"));
    worker.join(5000);

    assertTrue(interrupted.get());
    assertTrue(running.isCancelled("job-1"));
  }

  @Test
  void cancel_ofAJobNotRunningHere_isRememberedForWhenItsMessageArrives() {
    RunningJobs running = new RunningJobs();

    assertFalse(running.cancel("queued-job"));

    assertTrue(running.isCancelled("queued-job"));
    assertFalse(running.isCancelled("another"));
  }

  @Test
  void aCancellationIsForgottenAfterItsTtl() {
    RunningJobs running = new RunningJobs();
    Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
    running.cancel("old", t0);

    running.cancel("new", t0.plus(RunningJobs.TTL).plusSeconds(1));

    assertFalse(running.isCancelled("old"));
    assertTrue(running.isCancelled("new"));
  }

  @Test
  void unbind_clearsAStrayInterruptSoThePooledThreadIsClean() {
    RunningJobs running = new RunningJobs();
    running.bind("job-2");
    Thread.currentThread().interrupt();

    running.unbind("job-2");

    assertFalse(Thread.currentThread().isInterrupted());
  }
}
