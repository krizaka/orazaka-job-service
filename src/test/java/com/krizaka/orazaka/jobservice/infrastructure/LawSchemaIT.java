package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The law-corpus block of the <b>real</b> {@code infra/initdb/30-jobs-config.sql} on PostgreSQL
 * (ADR-089 §2 and §10, orazaka-job-service#67). The database is the one of {@link
 * AbstractContainerIntegrationTest}, the single source of the containers.
 *
 * <p>It is the story of the dev-deploy migration: a database that already holds the runtime-config
 * table and the research block, then the law block, then the law block again after an
 * administrator's edits.
 */
@SpringJUnitConfig(LawSchemaIT.Wiring.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LawSchemaIT extends AbstractContainerIntegrationTest {

  /** Only a JdbcTemplate on the singleton container; no application context is needed. */
  @Configuration
  static class Wiring {
    @Bean
    JdbcTemplate jdbcTemplate(Environment env) {
      return new JdbcTemplate(
          new DriverManagerDataSource(
              env.getRequiredProperty("spring.datasource.url"),
              env.getRequiredProperty("spring.datasource.username"),
              env.getRequiredProperty("spring.datasource.password")));
    }
  }

  private static final Pattern RESEARCH_BLOCK =
      Pattern.compile(
          "(?s)-- =+\\n-- GROUNDED RESEARCH \\(ADR-087.*?ON CONFLICT \\(source_key\\) DO NOTHING;");

  private static final Pattern LAW_BLOCK =
      Pattern.compile(
          "(?s)-- =+\\n-- LAW CORPUS \\(ADR-089.*?ON CONFLICT \\(source_key\\) DO NOTHING;");

  private static final Pattern RUNTIME_CONFIG_DDL =
      Pattern.compile("(?s)CREATE TABLE orazaka_runtime_config \\(.*?\\n\\);");

  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  @Autowired private JdbcTemplate jdbc;

  private static String lawBlock;
  private static String researchBlock;
  private static String runtimeConfigDdl;

  @BeforeAll
  static void readInitDb() throws IOException {
    String initDb =
        Files.readString(
            SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                .resolve("30-jobs-config.sql"));
    lawBlock = only(LAW_BLOCK, initDb, "the law block");
    researchBlock = only(RESEARCH_BLOCK, initDb, "the research block");
    runtimeConfigDdl = only(RUNTIME_CONFIG_DDL, initDb, "the runtime-config table");
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

  private String setting(String key) {
    return jdbc.queryForObject(
        "SELECT config_value FROM orazaka_runtime_config WHERE config_key = ?", String.class, key);
  }

  private void set(String key, String value) {
    jdbc.update(
        "UPDATE orazaka_runtime_config SET config_value = ? WHERE config_key = ?", value, key);
  }

  /** A database as dev has it before the migration: the settings table and the research block. */
  private void prerequisites() {
    // The container is a reused singleton: start from nothing of ours.
    jdbc.execute(
        "DROP TABLE IF EXISTS kind_demand, rule_proposal, law_retrieval, law_article_version,"
            + " law_import, law_instrument, research_consent, research_fetch, research_document,"
            + " research_source, orazaka_runtime_config CASCADE");
    jdbc.execute(
        "DROP FUNCTION IF EXISTS orazaka_config_value_ok(TEXT, TEXT, BIGINT, BIGINT, INTEGER)");
    jdbc.execute(runtimeConfigDdl);
    jdbc.execute(researchBlock);
  }

  @Test
  @Order(1)
  @DisplayName("on a database that has the research block, the migration creates the six tables")
  void createsTheTables() {
    prerequisites();
    int researchSettings =
        count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'research.%'");

    jdbc.execute(lawBlock);

    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name IN"
                    + " ('law_instrument','law_article_version','law_import','law_retrieval',"
                    + "'rule_proposal','kind_demand') ORDER BY table_name",
                String.class))
        .containsExactly(
            "kind_demand",
            "law_article_version",
            "law_import",
            "law_instrument",
            "law_retrieval",
            "rule_proposal");
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name ="
                    + " 'orazaka_runtime_config' AND column_name IN ('default_value','min_value',"
                    + "'max_value') ORDER BY column_name",
                String.class))
        .containsExactly("default_value", "max_value", "min_value");
    assertThat(count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'law.%'"))
        .isEqualTo(13);
    assertThat(
            count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'research.%'"))
        .as("the research rows are untouched")
        .isEqualTo(researchSettings);
  }

  @Test
  @Order(2)
  @DisplayName("every switch is off and both new sources are disabled")
  void everythingIsOff() {
    assertThat(setting("law.corpus.enabled")).isEqualTo("false");
    assertThat(setting("law.lecture.enabled")).isEqualTo("false");
    assertThat(
            jdbc.queryForList(
                "SELECT source_key FROM research_source WHERE provider IN ('law-corpus',"
                    + " 'donnees-quebec') ORDER BY source_key",
                String.class))
        .containsExactly("donnees-quebec", "law-corpus");
    assertThat(count("SELECT count(*) FROM research_source WHERE enabled"))
        .as("no source is enabled")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT allow_patterns[1] FROM research_source WHERE source_key = 'donnees-quebec'",
                String.class))
        .isEqualTo(
            "www.donneesquebec.ca/recherche/dataset/c8433300-f752-4815-8ea2-69cad416dd80/**");
    assertThat(
            jdbc.queryForObject(
                "SELECT cardinality(allow_patterns) FROM research_source WHERE source_key ="
                    + " 'law-corpus'",
                Integer.class))
        .as("the corpus source reads the database, not the network")
        .isZero();
  }

  @Test
  @Order(3)
  @DisplayName("a changed article text is a new row and the previous row gets in_force_to")
  void aChangedArticleIsANewVersion() {
    jdbc.update(
        "INSERT INTO law_instrument (jurisdiction, code, title_fr, official_url_template,"
            + " source_key) VALUES ('QC', 'CCQ-1991', 'Code civil du Québec',"
            + " 'https://www.legisquebec.gouv.qc.ca/fr/version/lc/CCQ-1991?code=se:{ref}',"
            + " 'donnees-quebec')");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO law_instrument (jurisdiction, code, official_url_template,"
                        + " source_key) VALUES ('QC', 'CCQ-1991', 'x', 'donnees-quebec')"))
        .as("one row per act: (jurisdiction, code) is unique")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO law_instrument (jurisdiction, code, official_url_template,"
                        + " source_key) VALUES ('XX', 'C-1', 'x', 'donnees-quebec')"))
        .as("an unknown jurisdiction")
        .isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update(
        "INSERT INTO law_import (source_key, mode, dataset_sha256) VALUES ('donnees-quebec',"
            + " 'AUTOMATED', repeat('c', 64))");
    jdbc.update(
        "INSERT INTO law_import (source_key, mode, dataset_sha256) VALUES ('donnees-quebec',"
            + " 'AUTOMATED', repeat('d', 64))");
    List<String> imports =
        jdbc.queryForList("SELECT id::text FROM law_import ORDER BY dataset_sha256", String.class);

    String insertVersion =
        "INSERT INTO law_article_version (instrument_id, ref, language, in_force_from, text,"
            + " content_hash, import_id) SELECT id, '1931', 'fr', ?::date, ?, ?, ?::uuid FROM"
            + " law_instrument WHERE code = 'CCQ-1991'";
    jdbc.update(insertVersion, "2020-01-01", "v1", HASH_A, imports.getFirst());
    assertThatThrownBy(() -> jdbc.update(insertVersion, "2024-06-01", "v2", HASH_B, imports.get(1)))
        .as("a second open version of the same article")
        .isInstanceOf(DataIntegrityViolationException.class);

    // The importer's order: close the old version, then write the new one.
    jdbc.update(
        "UPDATE law_article_version SET in_force_to = DATE '2024-06-01' WHERE ref = '1931' AND"
            + " content_hash = ?",
        HASH_A);
    jdbc.update(insertVersion, "2024-06-01", "v2", HASH_B, imports.get(1));

    assertThat(count("SELECT count(*) FROM law_article_version WHERE ref = '1931'")).isEqualTo(2);
    String asOf =
        "SELECT text FROM law_article_version WHERE ref = '1931' AND in_force_from <= ?::date"
            + " AND (in_force_to IS NULL OR ?::date < in_force_to)";
    assertThat(jdbc.queryForObject(asOf, String.class, "2023-01-01", "2023-01-01"))
        .as("asOf 2023 reads the old text")
        .isEqualTo("v1");
    assertThat(jdbc.queryForObject(asOf, String.class, "2025-01-01", "2025-01-01"))
        .as("asOf 2025 reads the new text")
        .isEqualTo("v2");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE law_article_version SET in_force_to = DATE '2019-01-01' WHERE"
                        + " content_hash = ?",
                    HASH_A))
        .as("a version cannot end before it starts")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForList(
                "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
                    + " AND a.attnum = ANY(i.indkey) WHERE i.indrelid ="
                    + " 'law_article_version'::regclass AND i.indisprimary ORDER BY"
                    + " array_position(i.indkey, a.attnum)",
                String.class))
        .containsExactly("instrument_id", "ref", "language", "content_hash");
  }

  @Test
  @Order(4)
  @DisplayName("the bounds refuse an out-of-range value and accept the edges")
  void theBoundsRejectOutOfRange() {
    for (String bad : new String[] {"0", "201", "-5", "12.5", "many", ""}) {
      assertThatThrownBy(() -> set("law.retrieval.max-articles", bad))
          .as("law.retrieval.max-articles = '" + bad + "' (1..200)")
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThatThrownBy(() -> set("law.derive.max-credits", "100001"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> set("law.import.max-article-chars", "999"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> set("law.retrieval.max-age-days", "6"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> set("law.corpus.enabled", "yes"))
        .as("a boolean is true or false")
        .isInstanceOf(DataIntegrityViolationException.class);
    String tooMany =
        IntStream.range(0, 201).mapToObj(i -> "QC:X-" + i).collect(Collectors.joining(","));
    assertThatThrownBy(() -> set("law.import.instruments", tooMany))
        .as("a list is at most 200 instruments")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE orazaka_runtime_config SET min_value = 10, max_value = 1 WHERE"
                        + " config_key = 'law.retrieval.max-articles'"))
        .as("min above max")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(setting("law.retrieval.max-articles")).as("nothing was written").isEqualTo("40");

    set("law.retrieval.max-articles", "1");
    set("law.retrieval.max-articles", "200");
    set("law.derive.max-credits", "0");
    assertThat(setting("law.derive.max-credits")).isEqualTo("0");
    assertThat(
            jdbc.queryForObject(
                "SELECT default_value FROM orazaka_runtime_config WHERE config_key ="
                    + " 'law.derive.max-credits'",
                String.class))
        .as("the default stays what the ADR says")
        .isEqualTo("2000");
    set("law.retrieval.max-articles", "40");
    set("law.derive.max-credits", "2000");
  }

  @Test
  @Order(5)
  @DisplayName("the review, import, retrieval and demand tables hold their rules")
  void theOtherTablesHoldTheirRules() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO law_import (source_key, mode) VALUES ('law-corpus', 'MANUAL')"))
        .as("a manual import says who and which file (its hash)")
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "INSERT INTO law_import (source_key, mode, imported_by, dataset_sha256, dataset_url)"
            + " VALUES ('law-corpus', 'MANUAL', 'admin-1', repeat('e', 64),"
            + " 'https://example.test/E-15.xml')");

    String insertProposal =
        "INSERT INTO rule_proposal (jurisdiction, kind_id, rule_id, proposal_revision, rule, law)"
            + " VALUES ('QC', 'visit-notice', 'notice-24h', ?, '{}', '{}')";
    jdbc.update(insertProposal, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM rule_proposal WHERE rule_id = 'notice-24h'", String.class))
        .isEqualTo("PROPOSED");
    assertThatThrownBy(() -> jdbc.update(insertProposal, 1))
        .as("one proposal per (jurisdiction, kind, rule, revision)")
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(insertProposal, 2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rule_proposal SET status = 'APPROVED' WHERE proposal_revision = 2"))
        .as("an approval names who and when")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rule_proposal SET status = 'MAYBE' WHERE proposal_revision = 2"))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE rule_proposal SET status = 'APPROVED', reviewed_by = 'admin-1', reviewed_at ="
            + " now() WHERE proposal_revision = 2");

    String countDemand =
        "INSERT INTO kind_demand (jurisdiction, kind_id, count) VALUES ('QC', 'UNKNOWN', 1) ON"
            + " CONFLICT (jurisdiction, kind_id) DO UPDATE SET count = kind_demand.count + 1,"
            + " last_seen = now()";
    jdbc.update(countDemand);
    jdbc.update(countDemand);
    assertThat(count("SELECT count FROM kind_demand WHERE kind_id = 'UNKNOWN'")).isEqualTo(2);

    String insertRetrieval =
        "INSERT INTO law_retrieval (jurisdiction, kind_id, as_of_bucket) VALUES ('QC',"
            + " 'visit-notice', DATE '2024-06-01')";
    jdbc.update(insertRetrieval);
    assertThatThrownBy(() -> jdbc.update(insertRetrieval))
        .as("one retrieval per (jurisdiction, kind, bucket)")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Order(6)
  @DisplayName("applied again: no error, no duplicate, and an administrator's edits stand")
  void appliesTwice() {
    jdbc.update("UPDATE research_source SET enabled = TRUE WHERE source_key = 'law-corpus'");
    set("law.corpus.enabled", "true");
    set("law.retrieval.max-articles", "77");
    int settings = count("SELECT count(*) FROM orazaka_runtime_config");
    int sources = count("SELECT count(*) FROM research_source");
    int versions = count("SELECT count(*) FROM law_article_version");
    int instruments = count("SELECT count(*) FROM law_instrument");

    jdbc.execute(lawBlock);
    jdbc.execute(lawBlock);

    assertThat(count("SELECT count(*) FROM orazaka_runtime_config")).isEqualTo(settings);
    assertThat(count("SELECT count(*) FROM research_source")).isEqualTo(sources);
    assertThat(count("SELECT count(*) FROM law_article_version")).isEqualTo(versions);
    assertThat(count("SELECT count(*) FROM law_instrument")).isEqualTo(instruments);
    assertThat(setting("law.corpus.enabled")).as("not switched back off").isEqualTo("true");
    assertThat(setting("law.retrieval.max-articles")).isEqualTo("77");
    assertThat(
            jdbc.queryForObject(
                "SELECT enabled FROM research_source WHERE source_key = 'law-corpus'",
                Boolean.class))
        .isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM pg_constraint WHERE conname IN"
                    + " ('ck_orazaka_runtime_config_bounds','ck_orazaka_runtime_config_value')"))
        .as("each constraint exists once")
        .isEqualTo(2);
  }

  @Test
  @Order(7)
  @DisplayName(
      "on an empty database the block applies twice from nothing (the migration's first run)")
  void freshThenTwice() {
    prerequisites();
    jdbc.execute(lawBlock);
    jdbc.execute(lawBlock);
    assertThat(count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'law.%'"))
        .isEqualTo(13);
    assertThat(setting("law.corpus.enabled")).isEqualTo("false");
    assertThat(count("SELECT count(*) FROM research_source WHERE enabled")).isZero();
  }
}
