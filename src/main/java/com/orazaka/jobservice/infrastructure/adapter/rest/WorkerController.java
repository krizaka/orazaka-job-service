package com.orazaka.jobservice.infrastructure.adapter.rest;

import com.orazaka.jobs.domain.model.WorkerRegistration;
import com.orazaka.jobservice.application.service.WorkerRegistryService;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a worker announces itself and says it is still alive.
 *
 * <p>The registration half of the worker protocol ({@code docs/WORKER_PROTOCOL.md}). A worker of
 * any language posts here at boot and heartbeats afterwards; nothing about the contract is Java, by
 * design (ADR-037 §3.4).
 *
 * <p>{@code /internal/v1}: service-to-service, gated on the {@code SERVICE} authority (ADR-035).
 *
 * <p><b>These endpoints must stay cheap and must never gate work.</b> A worker whose registration
 * fails still consumes — the registry is advisory. That is a property of the worker's client code,
 * but it is stated here because a future author reading only this class would otherwise be tempted
 * to make registration a precondition for something.
 */
@RestController
@RequestMapping("/internal/v1/workers")
class WorkerController {

  private final WorkerRegistryService workerRegistryService;

  WorkerController(WorkerRegistryService workerRegistryService) {
    this.workerRegistryService =
        Objects.requireNonNull(workerRegistryService, "WorkerRegistryService cannot be null");
  }

  /**
   * Registers a worker, or refreshes what is known about it.
   *
   * @param registration the worker's declared identity, family, bindings, version and concurrency
   */
  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void register(@RequestBody WorkerRegistration registration) {
    workerRegistryService.register(registration);
  }

  /**
   * Records a heartbeat from an already-registered worker.
   *
   * @param workerName the worker's declared name
   * @return {@code 204} when recorded, {@code 404} when the worker never registered — which tells a
   *     worker that lost a registration race to register again rather than heartbeat into the void
   */
  @PostMapping("/{workerName}/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  org.springframework.http.ResponseEntity<Void> heartbeat(@PathVariable String workerName) {
    return workerRegistryService.heartbeat(workerName)
        ? org.springframework.http.ResponseEntity.noContent().build()
        : org.springframework.http.ResponseEntity.notFound().build();
  }
}
