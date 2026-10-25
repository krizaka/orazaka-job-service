package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.core.domain.model.checks.CheckAudit;
import com.krizaka.orazaka.core.domain.model.checks.CheckOutcome;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckRefusal;
import com.krizaka.orazaka.core.domain.model.checks.KeySource;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.persistence.ExternalCheckAuditStore;
import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The external-checks block of the <b>real</b> {@code infra/initdb/30-jobs-config.sql} on
 * PostgreSQL (orazaka-packs#138), and the audit store over the table it creates. The database is
 * the one of {@link AbstractContainerIntegrationTest}, in a schema of its own.
 *
 * <p>It is the story of the dev-deploy migration: a registry created before the block (its
 * billable-capability check without {@code EXTERNAL_CHECK}), then the block, then the block again.
 */
@SpringJUnitConfig(ExternalCheckSchemaIT.Wiring.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExternalCheckSchemaIT extends AbstractContainerIntegrationTest {

  private static final String SCHEMA = "external_check_it";

  /** A JdbcTemplate on the singleton container, in this suite's own schema. */
  @Configuration
  static class Wiring {
    @Bean
    JdbcTemplate jdbcTemplate(Environment env) {
      String url = env.getRequiredProperty("spring.datasource.url");
      String user = env.getRequiredProperty("spring.datasource.username");
      String secret = env.getRequiredProperty("spring.datasource.password");
      new JdbcTemplate(new DriverManagerDataSource(url, user, secret))
          .execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE; CREATE SCHEMA " + SCHEMA);
      String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
      return new JdbcTemplate(new DriverManagerDataSource(scoped, user, secret));
    }
  }

  private static final Pattern BLOCK =
      Pattern.compile(
          "(?s)-- =+\\n-- EXTERNAL CHECKS \\(orazaka-packs#138.*?ON CONFLICT \\(feature_key\\) DO"
              + " NOTHING;");

  private static final Pattern CAPABILITIES_DDL =
      Pattern.compile("(?s)CREATE TABLE orazaka_capabilities \\(.*?\\n\\);");

  @Autowired private JdbcTemplate jdbc;

  private static String block;
  private static String capabilitiesDdl;

  @BeforeAll
  static void readInitDb() throws IOException {
    String initDb =
        Files.readString(
            SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                .resolve("30-jobs-config.sql"));
    block = only(BLOCK, initDb, "the external-checks block");
    capabilitiesDdl = only(CAPABILITIES_DDL, initDb, "the capability registry");
  }

  private static String only(Pattern pattern, String text, String what) {
    Matcher found = pattern.matcher(text);
    // One match, or the pattern stopped reading the file and this suite would prove nothing.
    assertThat(found.find()).as(what + " is in the initdb").isTrue();
    String match = found.group();
    assertThat(found.find()).as(what + " appears once").isFalse();
    return match;
  }

  private int count(String sql) {
    Integer n = jdbc.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

  private static CheckAudit line(CheckOutcome outcome, CheckRefusal refusal) {
    boolean answered = refusal == null;
    return new CheckAudit(
        Instant.parse("2026-10-25T12:00:00Z"),
        "manager-1",
        UUID.fromString("7b0c1f8e-3c55-4c0e-9d2e-111111111111"),
        "mock",
        CheckPurpose.COURT_RECORDS,
        answered ? KeySource.PLATFORM : null,
        outcome,
        refusal,
        "a".repeat(64),
        answered ? 12L : null,
        answered ? "mock-fixture-01" : null);
  }

  @Test
  @Order(1)
  @DisplayName("on a registry that predates it, the block widens the check and seeds three rows")
  void migratesAnExistingRegistry() {
    jdbc.execute(capabilitiesDdl);
    // The registry as dev has it: the column's unnamed check, without EXTERNAL_CHECK.
    jdbc.execute(
        "ALTER TABLE orazaka_capabilities DROP CONSTRAINT"
            + " ck_orazaka_capabilities_billable_capability;"
            + " ALTER TABLE orazaka_capabilities ADD CONSTRAINT"
            + " orazaka_capabilities_billable_capability_check CHECK (billable_capability IN"
            + " ('CHAT','IMAGE','AUDIO','VIDEO','AGENT'))");

    jdbc.execute(block);

    assertThat(
            jdbc.queryForList(
                "SELECT feature_key FROM orazaka_capabilities WHERE handler_key = 'check.external'"
                    + " AND is_enabled AND billable_capability = 'EXTERNAL_CHECK' AND billable_unit"
                    + " = 'CALL' ORDER BY feature_key",
                String.class))
        .containsExactly(
            "orazaka.core.checks.courtrecords",
            "orazaka.core.checks.credit",
            "orazaka.core.checks.identity");
  }

  @Test
  @Order(2)
  @DisplayName("the block applies a second time without an error or a second row")
  void isIdempotent() {
    jdbc.execute(block);

    assertThat(count("SELECT count(*) FROM orazaka_capabilities")).isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM pg_constraint WHERE conname ="
                    + " 'ck_orazaka_capabilities_billable_capability'"))
        .isEqualTo(1);
  }

  @Test
  @Order(3)
  @DisplayName("the widened check still refuses a capability billing does not know")
  void stillRefusesAnUnknownCapability() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO orazaka_capabilities (feature_key, handler_key, routing_key,"
                        + " billable_capability) VALUES ('orazaka.x', 'x', 'job.text.process',"
                        + " 'FREE')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Order(4)
  @DisplayName("the store appends an answered line and a refused one, as the engine wrote them")
  void theStoreAppends() {
    ExternalCheckAuditStore store = new ExternalCheckAuditStore(jdbc);

    store.record(line(CheckOutcome.MATCH, null));
    store.record(line(CheckOutcome.REFUSED, CheckRefusal.NO_CONSENT));

    Map<String, Object> answered =
        jdbc.queryForMap(
            "SELECT actor_id, run_id::text AS run_id, provider, purpose, key_source, outcome,"
                + " refusal, subject_ref, duration_ms, provider_ref FROM external_check_audit"
                + " WHERE outcome = 'MATCH'");
    assertThat(answered)
        .containsEntry("actor_id", "manager-1")
        .containsEntry("run_id", "7b0c1f8e-3c55-4c0e-9d2e-111111111111")
        .containsEntry("purpose", "COURT_RECORDS")
        .containsEntry("key_source", "PLATFORM")
        .containsEntry("refusal", null)
        .containsEntry("subject_ref", "a".repeat(64))
        .containsEntry("duration_ms", 12L)
        .containsEntry("provider_ref", "mock-fixture-01");
    assertThat(
            count(
                "SELECT count(*) FROM external_check_audit WHERE outcome = 'REFUSED' AND refusal ="
                    + " 'NO_CONSENT' AND key_source IS NULL AND duration_ms IS NULL"))
        .isEqualTo(1);
  }

  @Test
  @Order(5)
  @DisplayName("a line is never rewritten: an update is refused")
  void aLineIsNeverRewritten() {
    assertThatThrownBy(() -> jdbc.update("UPDATE external_check_audit SET outcome = 'NO_MATCH'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  @Order(6)
  @DisplayName("a refusal comes with REFUSED, and REFUSED with a refusal")
  void aRefusalComesWithRefused() {
    String ref = "b".repeat(64);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO external_check_audit (at, provider, purpose, outcome,"
                        + " subject_ref) VALUES (now(), 'mock', 'CREDIT', 'REFUSED', ?)",
                    ref))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
