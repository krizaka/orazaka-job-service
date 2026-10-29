package com.krizaka.orazaka.jobservice.infrastructure.adapter.research;

import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchRequest;
import com.krizaka.orazaka.tools.domain.model.law.LawTargetRef;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The inputs of the capability's step ({@code input_schema} of {@code
 * orazaka.core.research.grounded}): {@code sources}, {@code jurisdiction}, {@code asOf}, {@code
 * language}, {@code question}, {@code targets} and {@code document}, read from the payload with
 * their types checked. A value outside the contract is an {@link IllegalArgumentException} whose
 * message names the field and never the value.
 */
final class GroundedResearchInput {

  private GroundedResearchInput() {}

  static GroundedResearchRequest parse(JobCommand command, JobExecutionContext context) {
    Map<String, Object> payload = command.payload();
    return new GroundedResearchRequest(
        strings(payload.get("sources"), "sources"),
        text(payload.get("jurisdiction"), "jurisdiction"),
        day(payload.get("asOf")),
        text(payload.get("language"), "language"),
        text(payload.get("question"), "question"),
        targets(payload.get("targets")),
        text(payload.get("document"), "document"),
        runId(payload.get("runId"), command.jobId()),
        context.actorId());
  }

  private static String text(Object value, String field) {
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException(field + " must be text");
  }

  private static List<String> strings(Object value, String field) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(field + " must be a list of text");
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (item == null) {
        throw new IllegalArgumentException(field + " must not hold a null");
      }
      out.add(text(item, field));
    }
    return out;
  }

  private static LocalDate day(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(text(value, "asOf"));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("asOf must be a date, YYYY-MM-DD");
    }
  }

  private static List<LawTargetRef> targets(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("targets must be a list");
    }
    List<LawTargetRef> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("a target must be an object");
      }
      out.add(
          new LawTargetRef(
              text(map.get("instrument"), "instrument"), strings(map.get("refs"), "refs")));
    }
    return out;
  }

  /** The Studio run the step serves; a job of no run is its own run. */
  private static UUID runId(Object value, String jobId) {
    String raw = value == null ? jobId : text(value, "runId");
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException notAUuid) {
      return UUID.nameUUIDFromBytes(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
