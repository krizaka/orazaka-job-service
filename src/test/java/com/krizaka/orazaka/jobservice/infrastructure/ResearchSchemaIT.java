package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The grounded-research block of the <b>real</b> {@code infra/initdb/30-jobs-config.sql} on
 * PostgreSQL (ADR-087 slice 1, orazaka-job-service#62): it applies on an empty database with its
 * one prerequisite ({@code orazaka_runtime_config}), it applies a second time without touching what
 * an administrator changed in between, every source is seeded disabled, and the constraints the
 * design states are the table's, not a comment's.
 *
 * <p>The tests run in order because they are one story: a fresh database, then an administrator's
 * edits, then the same block again — which is exactly what {@code dev-deploy research-tables} does
 * on a database that already has the block.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResearchSchemaIT {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("orazaka_db")
          .withUsername("postgres")
          .withPassword("postgres");

  /** The block, from its banner to the last seed: what dev-deploy copies into its migration. */
  private static final Pattern RESEARCH_BLOCK =
      Pattern.compile(
          "(?s)-- =+\\n-- GROUNDED RESEARCH \\(ADR-087.*?ON CONFLICT \\(source_key\\) DO NOTHING;");

  /** The one table the block writes into without creating it. */
  private static final Pattern RUNTIME_CONFIG_DDL =
      Pattern.compile("(?s)CREATE TABLE orazaka_runtime_config \\(.*?\\n\\);");

  private static JdbcTemplate jdbcTemplate;
  private static String block;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    jdbcTemplate =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    String initDb = readInitDb();
    block = only(RESEARCH_BLOCK, initDb, "the grounded-research block");
    jdbcTemplate.execute(only(RUNTIME_CONFIG_DDL, initDb, "the runtime-config table"));
  }

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  private static String readInitDb() {
    try {
      return Files.readString(
          SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
              .resolve("30-jobs-config.sql"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String only(Pattern pattern, String text, String what) {
    Matcher found = pattern.matcher(text);
    // One match, or the pattern stopped reading the file and this suite would prove nothing.
    assertThat(found.find()).as(what + " is in the initdb").isTrue();
    String match = found.group();
    assertThat(found.find()).as(what + " appears once").isFalse();
    return match;
  }

  private static int count(String sql) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

  @Test
  @Order(1)
  @DisplayName(
      "on an empty database, the block creates the tables, the settings and five disabled sources")
  void appliesOnAnEmptyDatabase() {
    jdbcTemplate.execute(block);

    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'research_%'"
                + " ORDER BY table_name",
            String.class);
    assertThat(tables)
        .containsExactly(
            "research_consent", "research_document", "research_fetch", "research_source");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT config_value FROM orazaka_runtime_config WHERE config_key = 'research.enabled'",
                String.class))
        .isEqualTo("false");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT config_value FROM orazaka_runtime_config WHERE config_key = 'research.max-calls'",
                String.class))
        .isEqualTo("16");
    assertThat(count("SELECT count(*) FROM research_source")).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM research_source WHERE enabled")).isZero();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT source_key FROM research_source WHERE provider IN ('legisquebec','registraire')"
                    + " AND NOT enabled ORDER BY source_key",
                String.class))
        .containsExactly("legisquebec", "registraire");
  }

  @Test
  @Order(2)
  @DisplayName(
      "the cache is keyed by source, URL and hash; a document always has its source and its final URL")
  void theCacheIsKeyedBySource() {
    List<String> key =
        jdbcTemplate.queryForList(
            "SELECT a.attname FROM pg_index i JOIN pg_attribute a"
                + " ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)"
                + " WHERE i.indrelid = 'research_document'::regclass AND i.indisprimary"
                + " ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
    assertThat(key).containsExactly("source_id", "url", "content_hash");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO research_document (url, content_hash, final_url, http_status, bytes,"
                        + " text, expires_at) VALUES ('https://a.test/x', repeat('a', 64),"
                        + " 'https://a.test/x', 200, 1, 't', now())"))
        .as("a document without its source")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO research_document (source_id, url, content_hash, http_status,"
                        + " bytes, text, expires_at) SELECT id, 'https://a.test/x', repeat('a', 64),"
                        + " 200, 1, 't', now() FROM research_source WHERE source_key = 'canlii'"))
        .as("a document without its final URL")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Order(3)
  @DisplayName(
      "the constraints the design states hold: scope and scope_ref, refusal reasons, operations")
  void theConstraintsHold() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO research_source (source_key, kind, provider, scope) VALUES"
                        + " ('pack-scoped', 'FETCH', 'http', 'PACK')"))
        .as("a PACK source without its pack")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO research_fetch (operation, host, refused_reason) VALUES"
                        + " ('FETCH', 'a.test', 'BECAUSE')"))
        .as("an unknown refusal reason")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO research_fetch (operation, host) VALUES ('CRAWL', 'a.test')"))
        .as("an unknown operation")
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbcTemplate.update(
        "INSERT INTO research_fetch (operation, host, path_hash, refused_reason) VALUES"
            + " ('ROBOTS', 'a.test', repeat('b', 64), 'SSRF')");
    assertThat(count("SELECT count(*) FROM research_fetch WHERE refused_reason = 'SSRF'"))
        .isEqualTo(1);
  }

  @Test
  @Order(4)
  @DisplayName("applied a second time, the block changes nothing an administrator set")
  void appliesTwiceWithoutUndoingAdministratorEdits() {
    jdbcTemplate.update(
        "UPDATE research_source SET enabled = TRUE WHERE source_key = 'justice-laws'");
    jdbcTemplate.update(
        "UPDATE research_source SET freshness_seconds = 60 WHERE source_key = 'github-krizaka'");
    jdbcTemplate.update(
        "UPDATE orazaka_runtime_config SET config_value = 'STRICT'"
            + " WHERE config_key = 'research.verify.mode'");
    int settings =
        count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'research.%'");

    jdbcTemplate.execute(block);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enabled FROM research_source WHERE source_key = 'justice-laws'",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT freshness_seconds FROM research_source WHERE source_key = 'github-krizaka'",
                Integer.class))
        .isEqualTo(60);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT config_value FROM orazaka_runtime_config WHERE config_key = 'research.verify.mode'",
                String.class))
        .isEqualTo("STRICT");
    assertThat(count("SELECT count(*) FROM research_source")).isEqualTo(5);
    assertThat(
            count("SELECT count(*) FROM orazaka_runtime_config WHERE config_key LIKE 'research.%'"))
        .isEqualTo(settings);
    assertThat(count("SELECT count(*) FROM research_fetch"))
        .as("the audit is never touched")
        .isEqualTo(1);
  }
}
