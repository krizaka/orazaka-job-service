package com.orazaka.jobservice.application.service;

import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.persistence.domain.model.OutboxMessage;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.orazaka.persistence.domain.ports.inbound.OutboxStore;
import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the capability rows a pack contributes, and announces that they changed.
 *
 * <p>The job service owns {@code orazaka_capabilities}, so a pack's capabilities are registered
 * here rather than written into the table by whoever is installing (AGENTS.md §5, SEAM-001). It is
 * the write half of the surface {@code CapabilityController} already served reads on, and the point
 * of seam S4: a new capability becomes a row an installer sends, not a migration someone deploys.
 *
 * <p><b>The announcement is not optional.</b> Producers cache a capability's route on the dispatch
 * hot path; a row written without {@code evt.capability.changed} stays invisible to them for a full
 * TTL, so a freshly installed pack would appear to dispatch nowhere for the first minute of its
 * life — which reads exactly like the install having failed. Published through the transactional
 * outbox in the same transaction as the write (AGENTS.md §6), because a dual write would put that
 * same staleness back one layer down.
 */
@Service
public class CapabilityRegistryService {

  private static final String CAPABILITY_AGGREGATE = "capability";

  private final CapabilityManager capabilityManager;
  private final OutboxStore outboxStore;

  public CapabilityRegistryService(CapabilityManager capabilityManager, OutboxStore outboxStore) {
    this.capabilityManager =
        Objects.requireNonNull(capabilityManager, "CapabilityManager cannot be null");
    this.outboxStore = Objects.requireNonNull(outboxStore, "OutboxStore cannot be null");
  }

  /**
   * Creates or replaces one capability row.
   *
   * <p>Idempotent by key: re-installing a bundle rewrites the same row rather than failing, which
   * is what makes a partially applied install safe to retry instead of something to unpick by hand.
   *
   * @param featureKey the key from the path, authoritative over the declaration's own
   * @param declaration the row to write
   * @return the capability as stored
   */
  @Transactional
  public CapabilityDeclaration register(String featureKey, CapabilityDeclaration declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    CapabilityDeclaration saved = capabilityManager.save(toDto(featureKey, declaration));
    announce(saved.featureKey(), saved.enabled());
    return toDeclaration(saved);
  }

  /**
   * Removes one capability row.
   *
   * @param featureKey the capability to remove
   * @return {@code true} when a row was removed, {@code false} when there was none
   */
  @Transactional
  public boolean unregister(String featureKey) {
    if (!capabilityManager.delete(featureKey)) {
      return false;
    }
    // Announced as disabled rather than as deleted: a producer's cache holds a route, and the
    // only thing it needs to learn is that this key no longer dispatches. Adding a second event
    // shape for removal would mean every consumer learning two ways to hear the same news.
    announce(featureKey, false);
    return true;
  }

  private void announce(String featureKey, boolean enabled) {
    outboxStore.append(
        new OutboxMessage(
            CAPABILITY_AGGREGATE,
            featureKey,
            MessagingContract.EVENTS_EXCHANGE,
            MessagingContract.EVT_CAPABILITY_CHANGED,
            Map.of("featureKey", featureKey, "enabled", enabled)));
  }

  /**
   * The row as it will be stored — with the lane the PLATFORM chooses, never the one a registration
   * carries.
   *
   * <p>A pack that picked its own lane would pick the interactive one, and then everything would be
   * interactive (ADR-067 §3.2). The same principle as a blueprint being untrusted input even from
   * an admin: what a pack declares about ITS OWN work is a claim, and this one decides whose queue
   * it waits in. BATCH is the fail-closed direction — an unmeasured capability waits where waiting
   * is expected — and the platform reclassifies from the durations it records.
   */
  private static CapabilityDeclaration toDto(String featureKey, CapabilityDeclaration declaration) {
    return new CapabilityDeclaration(
        featureKey,
        declaration.handlerKey(),
        declaration.routingKey(),
        declaration.billableUnit(),
        declaration.billableCapability(),
        CapabilityRoute.DEFAULT_LATENCY_CLASS,
        // The contract travels as DECLARED, unlike the lane just above it: what a pack promises
        // about its own inputs and outputs is the pack's to state and the platform's to check,
        // whereas which queue its work waits in is the platform's to decide (ADR-069).
        declaration.inputSchema(),
        declaration.outputSchema(),
        declaration.enabled());
  }

  private static CapabilityDeclaration toDeclaration(CapabilityDeclaration dto) {
    return new CapabilityDeclaration(
        dto.featureKey(),
        dto.handlerKey(),
        dto.routingKey(),
        dto.billableUnit(),
        dto.billableCapability(),
        dto.latencyClass(),
        dto.inputSchema(),
        dto.outputSchema(),
        dto.enabled());
  }
}
