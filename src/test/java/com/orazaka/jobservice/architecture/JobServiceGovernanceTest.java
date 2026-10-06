package com.orazaka.jobservice.architecture;

import com.orazaka.persistence.infrastructure.config.MessagingContract;
import com.orazaka.test.architecture.ConfigBindingRules;
import com.orazaka.test.architecture.GovernanceRules;
import com.orazaka.test.architecture.LaneCoherenceRules;
import com.orazaka.test.architecture.LoggedContentRules;
import com.orazaka.test.architecture.PackPurityRules;
import com.orazaka.test.architecture.RunSurfaceRules;
import com.orazaka.test.architecture.SourceFileScanner;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Governance guardrails for orazaka-job-service — the asynchronous executor host.
 *
 * <p>This suite is the last of the nine, and it arrived after the module it guards had already
 * earned it: the {@code contains()} chain that chose an upload's filename from fragments of a
 * feature key lived here, in {@code JobListener}, through phases A to C. It was found by reading,
 * not by a build, because this was the one service module with no architecture suite at all — so
 * the rules the other eight enforce repository-wide were, from here, unenforceable locally.
 *
 * <p>The set mirrors {@code BillingServiceGovernanceTest} rather than the thinner {@code
 * KnowledgeServiceGovernanceTest}: the job service owns application services, adapters over four
 * transports and a support pack, so the naming and layout rules ([ERR-129], [ERR-130]) have
 * subjects here, and skipping them would leave the module's actual shape ungoverned.
 */
class JobServiceGovernanceTest {

  /**
   * The pack-purity and executor-coherence rules are repository-wide, not module-scoped: the worst
   * pack coupling lives in the Python media worker, which is in no Maven reactor, so a per-module
   * scan could never see it.
   */
  private static final Path REPOSITORY_ROOT =
      PackPurityRules.locateRepositoryRoot(Path.of(System.getProperty("user.dir")));

  private static final String BASE_PACKAGE = "com.orazaka.jobservice";

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  @Test
  @DisplayName("[DOOR-001] no inbound HTTP entry dispatches a job")
  void noInboundEntryDispatchesAJob() {
    RunSurfaceRules.assertNoInboundEntryDispatchesAJob();
  }

  @Test
  @DisplayName("[LANE-001] every capability declares the lane its routing key feeds")
  void everyCapabilityDeclaresItsLane() {
    // Asserted here because this service owns the two lanes and their consumers (ADR-067). The
    // bindings come from MessagingContract rather than being retyped: a rule holding its own copy
    // of the topology checks a second opinion about it, which is how PUBLISHED_FIELDS came to say
    // a capability publishes a field it never did.
    LaneCoherenceRules.assertEveryCapabilityDeclaresItsLane(
        REPOSITORY_ROOT,
        java.util.Map.of(
            "INTERACTIVE",
            java.util.List.of(
                MessagingContract.JOBS_INTERACTIVE_TEXT_BINDING,
                MessagingContract.JOBS_INTERACTIVE_MEDIA_BINDING),
            "BATCH",
            java.util.List.of(MessagingContract.JOBS_BATCH_BINDING)));
  }

  @Test
  @DisplayName("[SEAM-002] job-service depends on no foreign Tier-3 implementation")
  void dependsOnNoForeignTier3() {
    GovernanceRules.assertNoForeignTier3Dependency(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-103] one top-level type per file")
  void oneTopLevelClassPerFile() {
    GovernanceRules.assertOneTopLevelClassPerFile(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-104] no class carries the Orazaka prefix")
  void noRedundantPrefix() {
    GovernanceRules.assertNoRedundantPrefix(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-129] application/service holds only services")
  void servicePackageHoldsOnlyServices() {
    GovernanceRules.assertServicePackageOnlyServices(
        productionClasses, BASE_PACKAGE + ".application.service");
  }

  // GOV-006: [ERR-130] "domain holds no transport DTOs" is not invoked here: this service has no
  // domain
  // package, so the rule inspected no class. It runs in the modules that have one.

  @Test
  @DisplayName("[AGENTS.md §4] constructor injection only")
  void noFieldInjection() {
    GovernanceRules.assertNoFieldInjection(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[AGENTS.md §4] no System.out / System.err in production code")
  void noStandardStreams() {
    GovernanceRules.assertNoStandardStreams(productionClasses);
  }

  @Test
  @DisplayName("[ADR-035] no SecurityConfig opens /internal/** or /uploads/**")
  void internalAndMediaSurfacesStayAuthenticated() {
    GovernanceRules.assertNoPermitAllOnInternalOrUploads(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[ADR-035] /internal/v1 demands the SERVICE authority, not merely authentication")
  void internalSurfaceDemandsServiceAuthority() {
    GovernanceRules.assertInternalSurfaceRequiresServiceAuthority(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[AGENTS.md §4] requests run on virtual threads")
  void requestsRunOnVirtualThreads() {
    GovernanceRules.assertVirtualThreadsEnabled(Path.of(System.getProperty("user.dir")));
  }

  @Test
  @DisplayName("[PACK-002] no pack, studio or pack-capability key is a literal in engine code")
  void noPackKeyLiteralsInEngineCode() {
    GovernanceRules.assertNoPackKeyLiterals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[PACK-003] engine code never branches on a pack identifier")
  void noPackKeyConditionalsInEngineCode() {
    GovernanceRules.assertNoPackKeyConditionals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-001] every in-process capability's handler_key has an executor")
  void everyCapabilityHasAnExecutor() {
    GovernanceRules.assertEveryCapabilityHasAnExecutor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-002] every capability's routing_key is drained by a declared worker")
  void everyCapabilityIsDrained() {
    GovernanceRules.assertEveryCapabilityIsDrained(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[CAP-001] one record models a capability; the rest are narrow projections")
  void oneCapabilityModel() {
    GovernanceRules.assertOneCapabilityModel(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[CAP-002] the endpoint rule has one author")
  void endpointRuleHasOneAuthor() {
    GovernanceRules.assertEndpointRuleHasOneAuthor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[KIT-001] every service's security baseline says the same four things")
  void securityBaselineIsUniform() {
    GovernanceRules.assertSecurityBaselineIsUniform(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[KIT-002] dedup claims atomically and releases on failure")
  void dedupIsAtomic() {
    GovernanceRules.assertDedupIsAtomic(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[KIT-003] an outbox relay claims the rows it publishes")
  void outboxRelaysClaim() {
    GovernanceRules.assertOutboxRelaysClaim(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[KIT-004] the session secret minimum is the same in all five copies")
  void sessionSecretMinimumIsUniform() {
    GovernanceRules.assertSessionSecretMinimumIsUniform(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName(
      "[CFG-001] every type the configuration binder builds has a constructor it can choose")
  void configurationBindsUnambiguously() {
    ConfigBindingRules.assertConfigurationBindsUnambiguously();
    ConfigBindingRules.assertInjectableComponentsHaveOneConstructor();
  }

  @Test
  @DisplayName("[ERR-113] No Environment injection in production beans")
  void noEnvironmentInjection() {
    SourceFileScanner.assertNoEnvironmentInjection(Path.of("src", "main", "java"));
  }

  /** [LOG-001] no logging call takes a prompt, a response body or a message text (ADR-064). */
  @Test
  void noLoggingCallTakesContent() {
    LoggedContentRules.assertNoLoggingCallTakesContent();
  }
}
