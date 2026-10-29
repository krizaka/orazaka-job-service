package com.krizaka.orazaka.jobservice.infrastructure.adapter.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.core.domain.model.research.Citation;
import com.krizaka.orazaka.core.domain.model.research.Claim;
import com.krizaka.orazaka.core.domain.model.research.ClaimStatus;
import com.krizaka.orazaka.core.domain.model.research.GroundedAnswer;
import com.krizaka.orazaka.core.domain.model.research.GroundedSource;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.tools.application.service.GroundedResearchService;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchRequest;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchResult;
import com.krizaka.orazaka.tools.domain.model.law.GroundedResearchResult.Outcome;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The executor of {@code research.grounded}: it translates, the engine decides. No model runs. */
class GroundedResearchStrategyTest {

  private static final String RUN = "5b1f5b0e-6f4e-4d62-8b0a-0d6d1f6f2b11";

  private final GroundedResearchService engine = mock(GroundedResearchService.class);
  private final GroundedResearchStrategy strategy = new GroundedResearchStrategy(() -> engine);
  private final JobExecutionContext context =
      new JobExecutionContext("actor-1", "corr-1", Map.of(), Set.of());

  private static JobCommand command(Map<String, Object> payload) {
    return new JobCommand("job-1", "actor-1", "orazaka.core.research.grounded", payload);
  }

  private static Map<String, Object> payload() {
    return Map.of(
        "sources", List.of("law-corpus"),
        "jurisdiction", "QC",
        "asOf", "2026-10-20",
        "language", "fr",
        "question", "Quel préavis ?",
        "targets", List.of(Map.of("instrument", "CCQ-1991", "refs", List.of("1932"))),
        "runId", RUN);
  }

  @Test
  @DisplayName("the handler key is the one the seeded capability names")
  void handler_key() {
    assertThat(strategy.handlerKey()).isEqualTo("research.grounded");
  }

  @Test
  @DisplayName("the step inputs become the engine's request, with the actor and the run")
  void inputs_become_the_request() throws Exception {
    when(engine.research(any()))
        .thenReturn(GroundedResearchResult.refused(Outcome.NO_SOURCES, List.of()));

    strategy.execute(command(payload()), context);

    ArgumentCaptor<GroundedResearchRequest> request =
        ArgumentCaptor.forClass(GroundedResearchRequest.class);
    verify(engine).research(request.capture());
    assertThat(request.getValue().sources()).containsExactly("law-corpus");
    assertThat(request.getValue().asOf()).isEqualTo(LocalDate.parse("2026-10-20"));
    assertThat(request.getValue().targets())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.instrument()).isEqualTo("CCQ-1991");
              assertThat(t.refs()).containsExactly("1932");
            });
    assertThat(request.getValue().actorId()).isEqualTo("actor-1");
    assertThat(request.getValue().runId()).hasToString(RUN);
    assertThat(request.getValue().isLecture()).isFalse();
  }

  @Test
  @DisplayName("a refusal is a result, not an error: no claims, the outcome says why")
  void a_refusal_is_a_result() throws Exception {
    when(engine.research(any()))
        .thenReturn(GroundedResearchResult.refused(Outcome.NO_SOURCES, List.of("CCQ-1991 9999")));

    JobExecutionResult result = strategy.execute(command(payload()), context);

    assertThat(result.output())
        .containsEntry("outcome", "NO_SOURCES")
        .containsEntry("answer", "")
        .containsEntry("claims", List.of())
        .containsEntry("sources", List.of())
        .containsEntry("unknown", List.of("CCQ-1991 9999"));
  }

  @Test
  @DisplayName("an answer keeps its claims, citations and the sources that were read")
  void an_answer_keeps_its_citations() throws Exception {
    Citation citation =
        new Citation("https://x.test/a", "q", "h".repeat(64), "2026-10-24T12:00:00Z");
    Claim claim = new Claim("art. 1932", ClaimStatus.VERIFIED, null, List.of(citation));
    when(engine.research(any()))
        .thenReturn(
            new GroundedResearchResult(
                Outcome.ANSWERED,
                new GroundedAnswer("art. 1932", List.of(claim), List.of()),
                List.of(new GroundedSource("https://x.test/a", "CCQ, art. 1932", "law-corpus")),
                List.of()));

    Map<String, Object> out = strategy.execute(command(payload()), context).output();

    assertThat(out).containsEntry("outcome", "ANSWERED").containsEntry("answer", "art. 1932");
    assertThat(out.get("claims").toString())
        .contains("VERIFIED", "https://x.test/a", "h".repeat(64));
    assertThat(out.get("sources").toString()).contains("law-corpus", "CCQ, art. 1932");
  }

  @Test
  @DisplayName("while the engine's service is not wired the job fails before anything is read")
  void off_when_not_wired() {
    GroundedResearchStrategy off = new GroundedResearchStrategy(() -> null);

    assertThatThrownBy(() -> off.execute(command(payload()), context))
        .isInstanceOf(JobExecutionException.class)
        .hasMessageContaining("off");
  }

  @Test
  @DisplayName("an input outside the contract is refused without echoing its value")
  void a_bad_input_is_refused() {
    Map<String, Object> bad = new java.util.HashMap<>(payload());
    bad.put("asOf", "20th of October");

    assertThatThrownBy(() -> strategy.execute(command(bad), context))
        .isInstanceOf(JobExecutionException.class)
        .hasMessageContaining("asOf")
        .hasMessageNotContaining("20th");
    verify(engine, never()).research(any());
  }

  @Test
  @DisplayName("the engine's own refusal of an input (no asOf) is a job error, not a crash")
  void the_engine_may_refuse_the_input() {
    Map<String, Object> noDay = new java.util.HashMap<>(payload());
    noDay.remove("asOf");
    when(engine.research(any())).thenThrow(new IllegalArgumentException("asOf is required"));

    assertThatThrownBy(() -> strategy.execute(command(noDay), context))
        .isInstanceOf(JobExecutionException.class)
        .hasMessageContaining("asOf is required");
  }

  @Test
  @DisplayName("a document travels as data to the engine, which decides whether a lecture may run")
  void a_document_is_passed_on() throws Exception {
    Map<String, Object> withDocument = new java.util.HashMap<>(payload());
    withDocument.put("document", "Un avis.");
    when(engine.research(any()))
        .thenReturn(GroundedResearchResult.refused(Outcome.LECTURE_DISABLED, List.of()));

    JobExecutionResult result = strategy.execute(command(withDocument), context);

    ArgumentCaptor<GroundedResearchRequest> request =
        ArgumentCaptor.forClass(GroundedResearchRequest.class);
    verify(engine).research(request.capture());
    assertThat(request.getValue().isLecture()).isTrue();
    assertThat(result.output()).containsEntry("outcome", "LECTURE_DISABLED");
  }
}
