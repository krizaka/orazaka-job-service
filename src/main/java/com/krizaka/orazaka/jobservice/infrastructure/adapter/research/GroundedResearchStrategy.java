package com.krizaka.orazaka.jobservice.infrastructure.adapter.research;

import com.krizaka.orazaka.core.domain.model.research.Citation;
import com.krizaka.orazaka.core.domain.model.research.Claim;
import com.krizaka.orazaka.core.domain.model.research.GroundedSource;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import com.krizaka.orazaka.tools.application.service.GroundedResearchService;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchRequest;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs grounded research as a job step (ADR-087 slice 5): the capability {@code
 * orazaka.core.research.grounded}, whose row names the handler {@code research.grounded}.
 *
 * <p>The job plane only translates: the inputs of the step become a {@link
 * GroundedResearchRequest}, and the engine's {@link GroundedResearchService} answers or refuses. A
 * refusal is a normal result ({@code outcome} says why, {@code claims} is empty), never an error,
 * so a Studio step reads « no source » as an answer and not as a crash.
 *
 * <p>Fails closed: while the engine's service is not wired ({@code orazaka.law.grounded.enabled} is
 * false) and for an input outside the contract, the job fails before anything is read or asked. No
 * message carries a value of the payload (ADR-064).
 */
public final class GroundedResearchStrategy implements JobExecutor {

  private final Supplier<GroundedResearchService> service;

  /**
   * Creates the executor.
   *
   * @param service the engine's service, or a supplier that returns null while it is not wired
   */
  public GroundedResearchStrategy(Supplier<GroundedResearchService> service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  @Override
  public String handlerKey() {
    return "research.grounded";
  }

  @Override
  public JobExecutionResult execute(JobCommand command, JobExecutionContext context)
      throws JobExecutionException {
    GroundedResearchService engine = service.get();
    if (engine == null) {
      throw new JobExecutionException("grounded research is off on this instance");
    }
    GroundedResearchResult result;
    try {
      result = engine.research(GroundedResearchInput.parse(command, context));
    } catch (IllegalArgumentException invalid) {
      throw new JobExecutionException("grounded research input refused: " + invalid.getMessage());
    }
    return JobExecutionResult.of(outputsOf(result));
  }

  /** The step contract's outputs: answer, claims and sources, then why and what was unknown. */
  private static Map<String, Object> outputsOf(GroundedResearchResult result) {
    Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.put("answer", result.answer().answer());
    List<Map<String, Object>> claims = new ArrayList<>();
    for (Claim claim : result.answer().claims()) {
      claims.add(claimOf(claim));
    }
    outputs.put("claims", claims);
    outputs.put(
        "sources", result.sources().stream().map(GroundedResearchStrategy::sourceOf).toList());
    outputs.put("outcome", result.outcome().name());
    outputs.put("unknown", result.unknown());
    return outputs;
  }

  private static Map<String, Object> claimOf(Claim claim) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("text", claim.text());
    out.put("status", claim.status().name());
    if (claim.reason() != null) {
      out.put("reason", claim.reason().name());
    }
    out.put(
        "citations", claim.citations().stream().map(GroundedResearchStrategy::citationOf).toList());
    return out;
  }

  private static Map<String, Object> citationOf(Citation citation) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("url", citation.url());
    out.put("quote", citation.quote());
    out.put("contentHash", citation.contentHash());
    out.put("fetchedAt", citation.fetchedAt());
    return out;
  }

  private static Map<String, Object> sourceOf(GroundedSource source) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("url", source.url());
    out.put("title", source.title());
    out.put("sourceKey", source.sourceKey());
    return out;
  }
}
