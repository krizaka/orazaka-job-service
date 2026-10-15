package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.jobservice.application.service.WorkerInputService;
import com.krizaka.orazaka.jobservice.application.service.WorkerOutputService;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a worker on another host hands over what a job produced (ADR-077).
 *
 * <p>{@code /internal/v1}: service-to-service, gated on the {@code SERVICE} authority (ADR-035) — a
 * GPU droplet reaches it through the dev environment's relay, never through the edge.
 */
@RestController
@RequestMapping("/internal/v1/jobs")
class JobOutputController {

  private final WorkerOutputService workerOutputService;
  private final WorkerInputService workerInputService;

  JobOutputController(
      WorkerOutputService workerOutputService, WorkerInputService workerInputService) {
    this.workerInputService =
        Objects.requireNonNull(workerInputService, "WorkerInputService cannot be null");
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
   * The starting image of a job, for a worker that does not share the platform's disk.
   *
   * @param jobId the job the worker was sent
   * @param actorId the run's actor ({@code X-Orazaka-User-Id}); the image is read only from its
   *     files
   * @return {@code 200} with the image bytes, {@code 404} when this actor's job names none
   */
  @GetMapping("/{jobId}/input/image")
  ResponseEntity<byte[]> image(
      @PathVariable String jobId,
      @RequestParam(name = "asset", required = false) String assetId,
      @RequestHeader(name = "X-Orazaka-User-Id", required = false) String actorId) {
    // A Studio step reaches the worker without passing the listener: it names the asset it was
    // given. A job the listener archived is found by its own input.
    return (assetId != null && !assetId.isBlank()
            ? workerInputService.uploadedImage(actorId, assetId)
            : workerInputService.startingImage(jobId, actorId))
        .map(
            bytes ->
                ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Reads one of the actor's own outputs back (a step whose input is an earlier step's clip).
   *
   * @param jobId the job that produced it
   * @param filename its name within the job's output
   * @param actorId the run's actor ({@code X-Orazaka-User-Id}), who must own the job
   * @return {@code 200} with the plaintext, or {@code 404} — also for somebody else's job
   */
  @GetMapping("/{jobId}/output/{filename}")
  ResponseEntity<byte[]> read(
      @PathVariable String jobId,
      @PathVariable String filename,
      @RequestHeader(name = "X-Orazaka-User-Id", required = false) String actorId) {
    return workerOutputService
        .load(jobId, filename, actorId)
        .map(data -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(data))
        .orElseGet(() -> ResponseEntity.notFound().build());
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
