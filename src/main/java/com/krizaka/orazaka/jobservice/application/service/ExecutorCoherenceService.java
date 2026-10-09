package com.krizaka.orazaka.jobservice.application.service;

import com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Reports, at startup, every enabled capability whose {@code handler_key} no executor claims.
 *
 * <p>Routing became data in ADR-037, which left the job plane with <b>two</b> independent dispatch
 * discriminants: {@code routing_key} decides which process receives a message, {@code handler_key}
 * decides which code path runs it inside that process. A capability can be wrong in either, and the
 * two fail differently — a bad routing key strands a message on a queue nobody drains, a bad
 * handler key reaches the right process and dies there.
 *
 * <p>Phase A closed the first at publish and at dispatch. This closes the second at the moment it
 * becomes knowable: an executor set is only complete once the context is up, and with an open SPI
 * that set now depends on which jars are present. A build-time rule cannot see a jar that failed to
 * load, and it cannot see a capability row an operator added last night.
 *
 * <p><b>Logs, and does not refuse to start.</b> A capability with no executor is broken for that
 * capability; it is not a reason to take the whole job plane down and with it every capability that
 * does work. The build-time governance rule is what keeps the seeded rows honest; this is the
 * runtime signal for everything else, and phase F turns it into availability a user can see.
 */
public class ExecutorCoherenceService {

  private static final Logger logger = LoggerFactory.getLogger(ExecutorCoherenceService.class);

  private final List<JobExecutor> executors;
  private final CapabilityManager capabilityManager;

  /**
   * @param executors every registered executor
   * @param capabilityManager the capability registry
   */
  public ExecutorCoherenceService(
      List<JobExecutor> executors, CapabilityManager capabilityManager) {
    this.executors = List.copyOf(Objects.requireNonNull(executors, "executors required"));
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager cannot be null");
  }

  /** Runs once the context is up and the executor set is final. */
  @EventListener(ApplicationReadyEvent.class)
  public void reportUnservedCapabilities() {
    Set<String> served =
        executors.stream()
            .map(JobExecutor::handlerKey)
            .collect(Collectors.toCollection(TreeSet::new));
    List<CapabilityDeclaration> unserved =
        capabilityManager.findAll().stream()
            .filter(CapabilityDeclaration::enabled)
            .filter(capability -> !served.contains(capability.handlerKey()))
            .toList();

    logger.info("Job executors registered: {}", served);
    if (unserved.isEmpty()) {
      return;
    }
    for (CapabilityDeclaration capability : unserved) {
      logger.error(
          "Capability '{}' is enabled but its handler_key '{}' has no registered executor —"
              + " every job for it will fail at dispatch. Registered handler keys: {}",
          capability.featureKey(),
          capability.handlerKey(),
          served);
    }
  }
}
