package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.jobservice.application.service.JobRetentionService;
import com.krizaka.orazaka.jobservice.infrastructure.support.PathResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine surface of the right to erasure (orazaka#301): the Studio service, which owns the runs,
 * tells the job plane which of a person's jobs to erase, and this plane — which owns the rows and
 * the files — does it.
 *
 * <p>{@code /internal/v1}: service-to-service, gated on the {@code SERVICE} authority by the
 * security baseline (ADR-035). A person never calls this; they call the Studio service, which
 * checks they own the runs the job ids come from.
 */
@RestController
@RequestMapping("/internal/v1/jobs")
class JobErasureController {

  private final JobRetentionService retention;
  private final Path uploadRoot;

  JobErasureController(
      JobRetentionService retention,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir) {
    this.retention = Objects.requireNonNull(retention, "JobRetentionService cannot be null");
    this.uploadRoot = PathResolver.resolve(uploadDir);
  }

  /**
   * Erases jobs of one person: rows, files and the durable copy. Idempotent.
   *
   * @param request whose jobs, and which
   * @return {@code 200} with the counts; {@code 503} when some files could not be deleted (their
   *     rows are kept, so asking again finishes the job)
   */
  @PostMapping("/erase")
  ResponseEntity<ErasureResponse> erase(@RequestBody ErasureRequest request) {
    JobRetentionService.Erasure done =
        retention.erase(uploadRoot, request.userId(), request.jobIds());
    ErasureResponse body = new ErasureResponse(done.erased(), done.failed());
    return ResponseEntity.status(
            done.failed() == 0 ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(body);
  }

  /**
   * @param userId the person whose jobs these are
   * @param jobIds the jobs to erase
   */
  record ErasureRequest(String userId, List<String> jobIds) {}

  /**
   * @param erased jobs gone
   * @param failed jobs kept because their files could not be deleted yet
   */
  record ErasureResponse(int erased, int failed) {}
}
