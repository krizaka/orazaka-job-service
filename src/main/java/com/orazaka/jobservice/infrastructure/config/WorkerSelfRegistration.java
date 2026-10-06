package com.orazaka.jobservice.infrastructure.config;

import com.orazaka.jobs.domain.model.WorkerRegistration;
import com.orazaka.jobservice.application.service.WorkerRegistryService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Registers the job service in the worker registry from its own {@code worker.yaml}.
 *
 * <p>The job service is a worker like any other: it drains {@code job.media.*} and {@code
 * job.text.*} and runs them with in-process executors. A registry that only knew about "other
 * people's" workers would describe half the system, and phase F would read that half as the whole
 * (ADR-038).
 *
 * <p>It registers <b>in process</b> rather than over its own HTTP surface — calling itself through
 * the loopback to write its own table would be ceremony, and it would make startup depend on the
 * web layer being up. External workers use the REST endpoint because they have no alternative; this
 * one does.
 *
 * <p>Advisory here too: a failure to record the row is logged and dropped. The service executes
 * jobs whether or not the registry knows about it.
 */
@Component
public class WorkerSelfRegistration {

  private static final Logger logger = LoggerFactory.getLogger(WorkerSelfRegistration.class);

  private static final Pattern SCALAR = Pattern.compile("(?m)^(\\w+):\\s*\"?([^\"\\n#]+?)\"?\\s*$");
  private static final Pattern BINDING = Pattern.compile("(?m)^\\s*-\\s*\"?(job\\.[^\"\\s]+)\"?");

  private final WorkerRegistryService workerRegistryService;

  public WorkerSelfRegistration(WorkerRegistryService workerRegistryService) {
    this.workerRegistryService =
        Objects.requireNonNull(workerRegistryService, "WorkerRegistryService cannot be null");
  }

  /** Records this service's declaration once the context is up. */
  @EventListener(ApplicationReadyEvent.class)
  public void registerSelf() {
    try {
      workerRegistryService.register(readDeclaration());
    } catch (Exception e) {
      // Advisory: the registry is an availability signal, not a precondition for executing jobs.
      logger.error("Could not register the job service as a worker — continuing", e);
    }
  }

  /**
   * Keeps this service's row fresh.
   *
   * <p>Registering once is not enough and the acceptance gate proved it: the sweeper marks anything
   * unheard-from past the horizon {@code STALE}, so a job service that only registered at startup
   * read as dead ninety seconds later — and phase F would have greyed out every core capability on
   * the strength of it. A worker that does not heartbeat is indistinguishable from one that died,
   * which is the whole point of the horizon.
   *
   * <p>Re-registers when the row has gone: a purged registry, or a sweep that removed it, must heal
   * without a restart.
   */
  @Scheduled(fixedDelayString = "${orazaka.jobs.worker-heartbeat-interval:30000}")
  public void heartbeat() {
    try {
      if (!workerRegistryService.heartbeat(readDeclaration().name())) {
        workerRegistryService.register(readDeclaration());
      }
    } catch (Exception e) {
      logger.warn("Worker heartbeat failed — continuing", e);
    }
  }

  /**
   * Reads {@code worker.yaml} from the classpath.
   *
   * <p>Parsed with two regexes rather than a YAML library: the file is five scalars and a list, the
   * service has no YAML dependency of its own, and adding one to read a file this shape would be a
   * dependency bought for nothing. The schema is fixed by {@code docs/WORKER_PROTOCOL.md} §6, and
   * {@link WorkerRegistration} rejects anything malformed.
   */
  private static WorkerRegistration readDeclaration() throws IOException {
    String yaml;
    try (InputStream stream = new ClassPathResource("worker.yaml").getInputStream()) {
      yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String body = yaml.replaceAll("(?m)^#.*$", "");
    List<String> bindings = new ArrayList<>();
    Matcher binding = BINDING.matcher(body);
    while (binding.find()) {
      bindings.add(binding.group(1));
    }
    return new WorkerRegistration(
        scalar(body, "name"),
        scalar(body, "family"),
        bindings,
        scalar(body, "version"),
        Integer.parseInt(scalar(body, "concurrency")));
  }

  private static String scalar(String yaml, String key) {
    Matcher matcher = SCALAR.matcher(yaml);
    while (matcher.find()) {
      if (matcher.group(1).equals(key)) {
        return matcher.group(2).trim();
      }
    }
    throw new IllegalStateException("worker.yaml is missing required field '" + key + "'");
  }
}
