package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.jobservice.infrastructure.adapter.checks.ExternalCheckStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three external checks are seeded enabled, routed by {@code handler_key} to the executor that
 * serves them, billed per {@code CALL} as {@code EXTERNAL_CHECK}, with the step contract of {@code
 * orazaka-packs}' {@code tests/contracts/orazaka-core-checks.json} (orazaka-packs#138). Read as
 * text, like orazaka-studio's {@code BlueprintFitnessTest} reads it; {@code ExternalCheckSchemaIT}
 * applies the block on PostgreSQL.
 */
class SeedExternalChecksTest {

  private static final String SEED = read();

  /** The row's first line, then its two contracts, in the seed's dollar-quoted form. */
  private static final Pattern ROW =
      Pattern.compile(
          "\\('(?<key>orazaka\\.core\\.checks\\.[a-z]+)', '(?<handler>[a-z.]+)', '(?<route>[a-z.]+)',"
              + " '(?<unit>[A-Z_]+)', '(?<billed>[A-Z_]+)', '(?<lane>[A-Z]+)',\\n"
              + "\\s*\\$\\$(?<in>\\{.*?})\\$\\$::jsonb,\\n\\s*\\$\\$(?<out>\\{.*?})\\$\\$::jsonb,"
              + " (?<enabled>true|false)\\)");

  private record Row(
      String handler,
      String route,
      String unit,
      String billed,
      String lane,
      JsonNode in,
      JsonNode out,
      boolean enabled) {}

  private static String read() {
    try {
      return Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Row> rows() {
    JsonMapper json = JsonMapper.builder().build();
    Map<String, Row> rows = new LinkedHashMap<>();
    Matcher row = ROW.matcher(SEED);
    while (row.find()) {
      rows.put(
          row.group("key"),
          new Row(
              row.group("handler"),
              row.group("route"),
              row.group("unit"),
              row.group("billed"),
              row.group("lane"),
              json.readTree(row.group("in")),
              json.readTree(row.group("out")),
              Boolean.parseBoolean(row.group("enabled"))));
    }
    return rows;
  }

  private static Set<String> properties(JsonNode schema) {
    Set<String> names = new TreeSet<>();
    schema.get("properties").properties().forEach(entry -> names.add(entry.getKey()));
    return names;
  }

  @Test
  @DisplayName("the three keys are seeded, enabled, on the executor's handler key")
  void theThreeKeysAreSeeded() {
    String handler =
        new ExternalCheckStrategy(
                new com.krizaka.orazaka.core.infrastructure.checks.MockConnector(),
                new com.krizaka.orazaka.jobservice.infrastructure.adapter.checks
                    .MockProviderKeyStore(),
                line -> {},
                Optional::empty,
                Clock.systemUTC())
            .handlerKey();
    Map<String, Row> rows = rows();

    assertThat(rows)
        .containsOnlyKeys(
            "orazaka.core.checks.courtrecords",
            "orazaka.core.checks.credit",
            "orazaka.core.checks.identity");
    assertThat(rows.values())
        .allSatisfy(
            row -> {
              assertThat(row.enabled()).isTrue();
              assertThat(row.handler()).isEqualTo(handler);
              assertThat(row.route()).isEqualTo("job.text.process");
              assertThat(row.unit()).isEqualTo("CALL");
              assertThat(row.billed()).isEqualTo("EXTERNAL_CHECK");
              assertThat(row.lane()).isEqualTo("INTERACTIVE");
            });
  }

  @Test
  @DisplayName("each row declares the step contract of orazaka-core-checks.json")
  void eachRowDeclaresTheContract() {
    assertThat(rows().values())
        .allSatisfy(
            row -> {
              assertThat(properties(row.in())).containsExactly("consentedPurposes", "subject");
              assertThat(properties(row.in().get("properties").get("subject")))
                  .containsExactlyInAnyOrder(
                      "subjectId", "lastName", "firstName", "birthDate", "address");
              assertThat(properties(row.out()))
                  .containsExactlyInAnyOrder(
                      "outcome", "refusal", "category", "providerRef", "calls", "resultJson");
            });
  }

  @Test
  @DisplayName(
      "EXTERNAL_CHECK and NONE are billing declarations the table accepts, fresh or migrated")
  void theConstraintAcceptsExternalCheck() {
    assertThat(SEED)
        .containsPattern(
            "CONSTRAINT ck_orazaka_capabilities_billable_capability\\s+CHECK \\(billable_capability"
                + " IN \\('CHAT','IMAGE','AUDIO','VIDEO','AGENT','EXTERNAL_CHECK','NONE'\\)\\)")
        .contains(
            "ALTER TABLE orazaka_capabilities DROP CONSTRAINT IF EXISTS"
                + " orazaka_capabilities_billable_capability_check;");
  }

  @Test
  @DisplayName("no subject pepper is seeded: no row, no check, until an operator seals one")
  void noPepperIsSeeded() {
    assertThat(SEED).doesNotContain("('checks.subject-pepper'");
  }

  @Test
  @DisplayName("the audit has no column for a name, a birth date, an address or a record")
  void theAuditKeepsNoPersonalData() {
    String from = SEED.substring(SEED.indexOf("CREATE TABLE IF NOT EXISTS external_check_audit ("));
    String audit = from.substring(0, from.indexOf("\n);"));

    assertThat(audit).contains("subject_ref   CHAR(64)     NOT NULL");
    assertThat(audit).doesNotContain("name", "birth", "address", "record", "payload");
  }
}
