package com.orazaka.jobservice.infrastructure.adapter.rest;

import com.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.jobservice.application.service.CapabilityRegistryService;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine surface of the capability registry: where does this capability's work run?
 *
 * <p>The job service owns {@code orazaka_capabilities}, so it answers for it. A producer in another
 * bounded context — the Studio saga today, any pack's dispatcher later — asks here rather than
 * reading a database it does not own (AGENTS.md §5, SEAM-001/002) and rather than keeping a second
 * copy of the mapping, which is how the copies drift.
 *
 * <p>{@code /internal/v1}: this is service-to-service, gated on the {@code SERVICE} authority by
 * {@code SecurityConfig} (ADR-035). It is a read of configuration, not of anyone's data.
 */
@RestController
@RequestMapping("/internal/v1/capabilities")
class CapabilityController {

  private final CapabilityRoutingClient capabilityRoutingClient;
  private final CapabilityRegistryService capabilityRegistryService;

  CapabilityController(
      CapabilityRoutingClient capabilityRoutingClient,
      CapabilityRegistryService capabilityRegistryService) {
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient cannot be null");
    this.capabilityRegistryService =
        Objects.requireNonNull(
            capabilityRegistryService, "CapabilityRegistryService cannot be null");
  }

  /**
   * Registers or replaces one capability a pack contributes.
   *
   * <p>The write half of this surface, and the reason a pack can add a capability without a deploy
   * (ADR-037 §3.3, seam S4). The path key is authoritative over any key in the body: letting a body
   * rename the resource would allow one install to overwrite a different capability entirely.
   *
   * @param featureKey the capability's key, authoritative over the body
   * @param declaration the row to write
   * @return the capability as stored
   */
  @PutMapping("/{featureKey}")
  CapabilityDeclaration register(
      @PathVariable String featureKey, @RequestBody CapabilityDeclaration declaration) {
    return capabilityRegistryService.register(featureKey, declaration);
  }

  /**
   * Removes one capability.
   *
   * <p>Exists so a failed pack install can compensate the rows it already wrote. A capability left
   * behind by a half-applied install is a row nothing references and a route nothing drains, which
   * [EXEC-001] would then fail the build on.
   *
   * @param featureKey the capability to remove
   * @return {@code 204} when removed, {@code 404} when there was no such row
   */
  @DeleteMapping("/{featureKey}")
  ResponseEntity<Void> unregister(@PathVariable String featureKey) {
    return capabilityRegistryService.unregister(featureKey)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /**
   * Resolves where one capability's work is executed.
   *
   * @param featureKey the capability key from the registry
   * @return its route
   * @throws UnroutableCapabilityException when no enabled row carries one — rendered as 404, never
   *     as a default route
   */
  @GetMapping("/{featureKey}/route")
  CapabilityRoute route(@PathVariable String featureKey) {
    return capabilityRoutingClient
        .route(featureKey)
        .orElseThrow(() -> new UnroutableCapabilityException(featureKey));
  }

  /**
   * A capability with no enabled route is absent, not a server error.
   *
   * <p>404 rather than 200-with-null so a client cannot mistake "no route" for "no answer yet" and
   * carry on: the caller's contract is to refuse the dispatch, and an empty body it forgot to check
   * would put the silent misroute back where it was removed from.
   *
   * @param failure the unresolved capability
   * @return the message naming it
   */
  @ExceptionHandler(UnroutableCapabilityException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String unroutable(UnroutableCapabilityException failure) {
    return failure.getMessage();
  }
}
