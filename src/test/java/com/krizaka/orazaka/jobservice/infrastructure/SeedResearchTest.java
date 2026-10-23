package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grounded research (ADR-087, orazaka-job-service#62) is data and is off until an administrator
 * turns it on. Read as text: the database applies it in the ITs (ProtectedJobRetentionIT).
 */
class SeedResearchTest {

  private static final String SEED = read();

  private static String read() {
    try {
      return Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String table(String name) {
    String from = SEED.substring(SEED.indexOf("CREATE TABLE IF NOT EXISTS " + name + " ("));
    return from.substring(0, from.indexOf("\n);"));
  }

  @Test
  @DisplayName("the four tables exist, the cache keyed by URL and content hash")
  void theTablesExist() {
    assertThat(table("research_source"))
        .contains("source_key", "allow_patterns", "deny_patterns", "allows_personal_data")
        .contains("credential_ref", "enabled              BOOLEAN      NOT NULL DEFAULT FALSE");
    assertThat(table("research_document"))
        .contains("PRIMARY KEY (url, content_hash)", "expires_at");
    assertThat(table("research_consent")).contains("purpose", "revoked_at");
  }

  @Test
  @DisplayName("the audit keeps a path hash and a reason, never a query or a path in clear")
  void theAuditKeepsNoContent() {
    String audit = table("research_fetch");
    assertThat(audit).contains("path_hash      CHAR(64)", "refused_reason", "cache_hit");
    assertThat(audit).doesNotContain(" query ", " path ", " url ");
  }

  @Test
  @DisplayName("research is off by default, and every seeded source is disabled")
  void everythingIsOffByDefault() {
    assertThat(SEED)
        .contains("('research.enabled', 'false', 'boolean'")
        .contains("('research.verify.mode', 'MARK', 'string'");
    String sources = SEED.substring(SEED.indexOf("INSERT INTO research_source"));
    sources = sources.substring(0, sources.indexOf("ON CONFLICT"));
    Matcher rows = Pattern.compile("\\n\\('([a-z-]+)',[^\\n]*?, (TRUE|FALSE)\\)").matcher(sources);
    int count = 0;
    while (rows.find()) {
      count++;
      assertThat(rows.group(2)).as(rows.group(1)).isEqualTo("FALSE");
    }
    assertThat(count).isEqualTo(5);
  }

  @Test
  @DisplayName("the citation verifier is a pipeline row, OFF by default (ADR-087 slice 2)")
  void theVerifierRowIsOff() {
    assertThat(SEED)
        .contains("('CitationVerifierInterceptor','Citation Verifier Interceptor',12, FALSE,");
  }

  @Test
  @DisplayName("the user agent is honest, never a browser's")
  void theUserAgentIsHonest() {
    assertThat(SEED)
        .contains("('research.user-agent', 'OrazakaResearch/")
        .doesNotContain("'research.user-agent', 'Mozilla");
  }
}
