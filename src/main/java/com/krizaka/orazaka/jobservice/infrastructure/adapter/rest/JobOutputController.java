package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.jobservice.application.service.WorkerOutputService;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a worker on another host hands over what a job produced (ADR-077).
 *
 * <p>{@code /internal/v1}: service-to-service, gated on the {@code SERVICE} authority (ADR-035) —
 * a GPU droplet reaches it through the dev environment's relay, never through the edge.
 */
@RestController
@RequestMapping("/internal/v1/jobs")
class JobOutputController {

  private final WorkerOutputService workerOutputService;

  JobOutputController(WorkerOutputService workerOutputService) {
    this.workerOutputService =
        Objects.requireNonNull(workerOutputService, "WorkerOutputService cannot be null");
  }

  /**
   * Stores one output file of a job, sealed, and answers the URL it is served under.
   *
   * @param jobId the job that produced it
   * @param filename its name within the job's output
   * @param data the file, as the request body
   * @param actorId the run's actor ({@code X-Orazaka-User-Id}), used only to adopt a missing row
   * @param featureKey the job's capability ({@code X-Orazaka-Feature-Key}), idem
   * @param dataClass the job's data class ({@code X-Orazaka-Data-Class}), idem
   * @return {@code 201} with {@code {"url": "/api/v1/assets/{jobId}/{filename}"}}
   */
  @PutMapping("/{jobId}/output/{filename}")
  ResponseEntity<Map<String, String>> store(
      @PathVariable String jobId,
      @PathVariable String filename,
      @RequestBody byte[] data,
      @RequestHeader(name = "X-Orazaka-User-Id", required = false) String actorId,
      @RequestHeader(name = "X-Orazaka-Feature-Key", required = false) String featureKey,
      @RequestHeader(name = "X-Orazaka-Data-Class", required = false) String dataClass) {
    String url = workerOutputService.store(jobId, filename, data, actorId, featureKey, dataClass);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", url));
  }

  /**
   * A malformed hand-over is the worker's to fix: {@code 400} with the reason.
   *
   * @param refused the reason
   * @return {@code 400}
   */
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> refused(IllegalArgumentException refused) {
    return ResponseEntity.badRequest().body(Map.of("error", refused.getMessage()));
  }
}
