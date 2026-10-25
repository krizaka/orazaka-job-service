package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.core.domain.model.checks.CheckAudit;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckRequest;
import com.krizaka.orazaka.core.domain.model.checks.CheckResult;
import com.krizaka.orazaka.core.domain.model.checks.KeySource;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckSubjectPepper;
import com.krizaka.orazaka.core.domain.ports.outbound.ExternalCheckConnector;
import com.krizaka.orazaka.core.infrastructure.checks.MockConnector;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp.InvalidJobInputException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The executor of {@code check.external} (orazaka-packs#138), on the engine's real door and the
 * mock connector: the step contract of {@code orazaka-packs}' {@code
 * tests/contracts/orazaka-core-checks.json}, the consent, the job's hold and the fail-closed paths.
 */
class ExternalCheckStrategyTest {

  private static final String COURTS = "orazaka.core.checks.courtrecords";
  private static final String CREDIT = "orazaka.core.checks.credit";
  private static final String IDENTITY = "orazaka.core.checks.identity";
  private static final UUID RUN = UUID.fromString("7b0c1f8e-3c55-4c0e-9d2e-111111111111");
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
  private static final JobExecutionContext ACTOR =
      new JobExecutionContext("manager-1", "corr-1", Map.of(), Set.of());

  private final AtomicInteger providerCalls = new AtomicInteger();
  private final List<CheckAudit> audit = new ArrayList<>();
  private final ExternalCheckConnector connector = counting(new MockConnector());

  private ExternalCheckStrategy strategy(CheckSubjectPepper pepper) {
    return new ExternalCheckStrategy(
        connector,
        new MockProviderKeyStore(),
        audit::add,
        pepper,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private final ExternalCheckStrategy strategy = strategy(() -> Optional.of(new byte[32]));

  @Test
  @DisplayName("the handler key is the one the three capability rows name")
  void handlerKey() {
    assertThat(strategy.handlerKey()).isEqualTo("check.external");
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "orazaka.core.checks.courtrecords, COURT_RECORDS",
    "orazaka.core.checks.credit, CREDIT",
    "orazaka.core.checks.identity, IDENTITY"
  })
  @DisplayName("each feature key runs its purpose, granted by that purpose alone")
  void eachKeyRunsItsPurpose(String featureKey, CheckPurpose purpose) throws Exception {
    JobExecutionResult result =
        strategy.execute(studioStep(featureKey, "Gagnon", List.of(purpose.name())), ACTOR);

    assertThat(result.output()).containsEntry("outcome", "NO_MATCH");
    assertThat(audit)
        .singleElement()
        .satisfies(line -> assertThat(line.purpose()).isEqualTo(purpose));
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  @DisplayName("a court-records match answers its category, its opaque reference and one call")
  void aMatchIsOneCall() throws Exception {
    JobExecutionResult result =
        strategy.execute(studioStep(COURTS, "Tremblay", List.of("COURT_RECORDS")), ACTOR);

    assertThat(result.output())
        .containsOnlyKeys("outcome", "category", "providerRef", "calls", "resultJson")
        .containsEntry("outcome", "MATCH")
        .containsEntry("category", "TAL_NON_PAYMENT")
        .containsEntry("providerRef", "mock-fixture-01")
        .containsEntry("calls", 1);
    assertThat(result.consumption()).isEqualTo(Map.of("calls", 1));
    assertThat(result.model()).as("the pricebook's model is the provider").isEqualTo("mock");
  }

  @Test
  @DisplayName("resultJson is the five fields as JSON text, an absent one written as null")
  void resultJsonIsTheSameObject() throws Exception {
    JobExecutionResult result =
        strategy.execute(studioStep(CREDIT, "Tremblay", List.of("CREDIT")), ACTOR);

    Map<String, Object> others = new HashMap<>(result.output());
    Object json = others.remove("resultJson");
    Map<String, Object> parsed = JsonMapper.builder().build().readValue((String) json, Map.class);
    Map<String, Object> expected = new HashMap<>(others);
    expected.put("refusal", null);
    assertThat(parsed).isEqualTo(expected);
    assertThat(others).containsEntry("category", "NONE").doesNotContainKey("refusal");
  }

  @ParameterizedTest(name = "{0} → {1}, no call charged")
  @CsvSource({"Roy, PROVIDER_ERROR", "Bouchard, TIMEOUT"})
  @DisplayName("a provider that did not answer reports calls 0")
  void noAnswerIsNoCall(String lastName, String outcome) throws Exception {
    JobExecutionResult result =
        strategy.execute(studioStep(IDENTITY, lastName, List.of("IDENTITY")), ACTOR);

    assertThat(result.output()).containsEntry("outcome", outcome).containsEntry("calls", 0);
    assertThat(result.consumption()).isEqualTo(Map.of("calls", 0));
  }

  @Test
  @DisplayName(
      "without the purpose's consent: REFUSED, NO_CONSENT, calls 0, the provider untouched")
  void noConsentIsRefused() throws Exception {
    // DOCUMENTS and CREDIT are consented, COURT_RECORDS is not.
    JobExecutionResult result =
        strategy.execute(studioStep(COURTS, "Tremblay", List.of("DOCUMENTS", "CREDIT")), ACTOR);

    assertThat(result.output())
        .containsEntry("outcome", "REFUSED")
        .containsEntry("refusal", "NO_CONSENT")
        .containsEntry("category", "NONE")
        .containsEntry("calls", 0)
        .doesNotContainKey("providerRef");
    assertThat(result.consumption()).isEqualTo(Map.of("calls", 0));
    assertThat(providerCalls).as("the provider is never asked").hasValue(0);
    assertThat(audit).singleElement().satisfies(line -> assertThat(line.refusal()).isNotNull());
  }

  @Test
  @DisplayName("no consentedPurposes at all grants nothing")
  void absentConsentGrantsNothing() throws Exception {
    Map<String, Object> payload = studioPayload("Tremblay", List.of());
    payload.remove("consentedPurposes");

    JobExecutionResult result = strategy.execute(command(COURTS, payload), ACTOR);

    assertThat(result.output()).containsEntry("refusal", "NO_CONSENT");
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  @DisplayName("a job no hold covers is refused BUDGET_EXHAUSTED before the provider is asked")
  void anUnheldJobIsRefused() throws Exception {
    Map<String, Object> payload = studioPayload("Tremblay", List.of("COURT_RECORDS"));
    payload.remove(JobCommand.DEFERRED_METERING_KEY);

    JobExecutionResult result = strategy.execute(command(COURTS, payload), ACTOR);

    assertThat(result.output())
        .containsEntry("outcome", "REFUSED")
        .containsEntry("refusal", "BUDGET_EXHAUSTED")
        .containsEntry("calls", 0);
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  @DisplayName("a job carrying its own hold runs, and the audit names the run and the actor")
  void aHeldJobRuns() throws Exception {
    Map<String, Object> payload = studioPayload("Gagnon", List.of("COURT_RECORDS"));
    payload.remove(JobCommand.DEFERRED_METERING_KEY);
    payload.put("holdId", "hold-138");

    strategy.execute(command(COURTS, payload), ACTOR);

    assertThat(audit)
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.runId()).isEqualTo(RUN);
              assertThat(line.actorId()).isEqualTo("manager-1");
              assertThat(line.keySource()).isEqualTo(KeySource.PLATFORM);
              assertThat(line.subjectRef()).hasSize(64).doesNotContain("subject-42");
              assertThat(line.at()).isEqualTo(NOW);
            });
  }

  @Test
  @DisplayName("a feature key that is not a check is refused, the provider untouched")
  void anUnknownKeyIsRefused() {
    JobCommand other = studioStep("orazaka.core.chat.completion", "Tremblay", List.of("CREDIT"));

    assertThatThrownBy(() -> strategy.execute(other, ACTOR))
        .isInstanceOf(JobExecutionException.class)
        .isNotInstanceOf(InvalidJobInputException.class)
        .hasMessageContaining("not an external check");
    assertThat(providerCalls).hasValue(0);
    assertThat(audit).isEmpty();
  }

  @Test
  @DisplayName("no sealed pepper: the job fails closed, nothing asked, nothing audited")
  void noPepperFailsClosed() {
    ExternalCheckStrategy unsealed = strategy(Optional::empty);

    assertThatThrownBy(
            () -> unsealed.execute(studioStep(COURTS, "Tremblay", List.of("COURT_RECORDS")), ACTOR))
        .isInstanceOf(JobExecutionException.class)
        .hasMessageContaining("external checks are off");
    assertThat(providerCalls).hasValue(0);
    assertThat(audit).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "subject is a text | subject must be an object",
        "lastName missing | subject.lastName is required",
        "firstName blank | subject.firstName is required",
        "birthDate not a date | subject.birthDate must be an ISO date",
        "address a number | subject.address must be text",
        "consentedPurposes a text | consentedPurposes must be a list"
      })
  @DisplayName("an input off the contract is INPUT_INVALID, and the message holds no value")
  void badInputIsInvalid(String breakage, String message) {
    Map<String, Object> payload = studioPayload("Tremblay", List.of("COURT_RECORDS"));
    @SuppressWarnings("unchecked")
    Map<String, Object> subject = (Map<String, Object>) payload.get("subject");
    Function<String, Object> broken =
        what ->
            switch (what) {
              case "subject is a text" -> payload.put("subject", "Tremblay, Marie");
              case "lastName missing" -> subject.remove("lastName");
              case "firstName blank" -> subject.put("firstName", " ");
              case "birthDate not a date" -> subject.put("birthDate", "Tremblay-1990");
              case "address a number" -> subject.put("address", 1234);
              default -> payload.put("consentedPurposes", "COURT_RECORDS Tremblay");
            };
    broken.apply(breakage);

    assertThatThrownBy(() -> strategy.execute(command(COURTS, payload), ACTOR))
        .isInstanceOf(InvalidJobInputException.class)
        .hasMessageContaining(message)
        .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("Tremblay", "Marie"));
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  @DisplayName("birthDate and address are optional, and a given birth date reaches the provider")
  void optionalFieldsAreRead() throws Exception {
    List<CheckRequest> seen = new ArrayList<>();
    ExternalCheckStrategy recording =
        new ExternalCheckStrategy(
            recording(seen),
            new MockProviderKeyStore(),
            audit::add,
            () -> Optional.of(new byte[32]),
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> payload = studioPayload("Gagnon", List.of("IDENTITY"));
    @SuppressWarnings("unchecked")
    Map<String, Object> subject = (Map<String, Object>) payload.get("subject");
    subject.put("birthDate", "1990-04-01");
    subject.put("address", "1 rue Principale, Montréal");

    recording.execute(command(IDENTITY, payload), ACTOR);

    assertThat(seen)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.subject().birthDate()).hasToString("1990-04-01");
              assertThat(request.subject().address()).isEqualTo("1 rue Principale, Montréal");
            });
  }

  // ── fixtures ────────────────────────────────────────────────────────────────────────────────

  /** A Studio step: no hold of its own, metered by its run (ADR-041), as the studio sends it. */
  private static JobCommand studioStep(String featureKey, String lastName, List<String> purposes) {
    return command(featureKey, studioPayload(lastName, purposes));
  }

  private static Map<String, Object> studioPayload(String lastName, List<String> purposes) {
    Map<String, Object> subject = new HashMap<>();
    subject.put("subjectId", "subject-42");
    subject.put("lastName", lastName);
    subject.put("firstName", "Marie");
    Map<String, Object> payload = new HashMap<>();
    payload.put("subject", subject);
    payload.put("consentedPurposes", purposes);
    payload.put("runId", RUN.toString());
    payload.put("stepId", "courts");
    payload.put(JobCommand.DEFERRED_METERING_KEY, Boolean.TRUE);
    return payload;
  }

  private static JobCommand command(String featureKey, Map<String, Object> payload) {
    return new JobCommand(
        "job-1", "manager-1", featureKey, "default", payload, DataClass.SENSITIVE);
  }

  /** The mock connector, with every provider call counted. */
  private ExternalCheckConnector counting(ExternalCheckConnector delegate) {
    return observing(delegate, request -> providerCalls.incrementAndGet());
  }

  /** The mock connector, with every request the provider receives kept. */
  private ExternalCheckConnector recording(List<CheckRequest> seen) {
    return observing(new MockConnector(), seen::add);
  }

  private static ExternalCheckConnector observing(
      ExternalCheckConnector delegate, java.util.function.Consumer<CheckRequest> observer) {
    return new ExternalCheckConnector() {
      @Override
      public String provider() {
        return delegate.provider();
      }

      @Override
      public CheckResult verifyCourtRecords(CheckRequest request) {
        observer.accept(request);
        return delegate.verifyCourtRecords(request);
      }

      @Override
      public CheckResult verifyCredit(CheckRequest request) {
        observer.accept(request);
        return delegate.verifyCredit(request);
      }

      @Override
      public CheckResult verifyIdentity(CheckRequest request) {
        observer.accept(request);
        return delegate.verifyIdentity(request);
      }
    };
  }
}
