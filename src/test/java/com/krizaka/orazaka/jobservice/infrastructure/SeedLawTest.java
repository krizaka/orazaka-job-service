package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The law corpus (ADR-089 §2 and §10, orazaka-job-service#67) is data and is off until an
 * administrator turns it on. Read as text here, so the pin holds without a database; {@code
 * LawSchemaIT} applies the same block on PostgreSQL.
 */
class SeedLawTest {

  private static final String SEED = read();
  private static final String LAW = SEED.substring(SEED.indexOf("-- LAW CORPUS (ADR-089"));

  private static String read() {
    try {
      return Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String table(String name) {
    String from = LAW.substring(LAW.indexOf("CREATE TABLE IF NOT EXISTS " + name + " ("));
    return from.substring(0, from.indexOf("\n);"));
  }

  @Test
  @DisplayName("the six tables exist, with the keys and uniqueness of ADR-089 §2")
  void theTablesExist() {
    assertThat(table("law_instrument")).contains("UNIQUE (jurisdiction, code)", "source_key");
    assertThat(table("law_article_version"))
        .contains("PRIMARY KEY (instrument_id, ref, language, in_force_from)", "in_force_to");
    assertThat(table("law_import")).contains("'AUTOMATED','MANUAL'", "dataset_sha256");
    assertThat(table("law_retrieval"))
        .contains("PRIMARY KEY (jurisdiction, kind_id, as_of_bucket)");
    assertThat(table("rule_proposal"))
        .contains("'PROPOSED','REJECTED_AUTO','APPROVED','REJECTED','PROMOTED','SUPERSEDED'");
    assertThat(table("kind_demand")).contains("PRIMARY KEY (jurisdiction, kind_id)");
  }

  @Test
  @DisplayName("every switch is off: the corpus, the lecture and both sources")
  void everySwitchIsOff() {
    assertThat(LAW)
        .contains("('law.corpus.enabled', 'false', 'boolean'")
        .contains("('law.lecture.enabled', 'false', 'boolean'");
    // A setting's value and its default say the same thing.
    Matcher rows =
        Pattern.compile("\\('(law\\.[^']+)', '([^']*)', '[a-z]+', '[^']*', '([^']*)'").matcher(LAW);
    int seen = 0;
    while (rows.find()) {
      assertThat(rows.group(3))
          .as(rows.group(1) + " starts at its default")
          .isEqualTo(rows.group(2));
      seen++;
    }
    assertThat(seen).as("the law.* rows").isEqualTo(13);
    assertThat(LAW)
        .contains("('law-corpus', 'OFFICIAL', 'law-corpus', NULL, '{}', 86400, FALSE)")
        .contains("604800, FALSE)")
        .doesNotContain(", TRUE)");
  }

  @Test
  @DisplayName("the bounds are the ADR's, and the database enforces them")
  void theBoundsAreTheAdrs() {
    assertThat(LAW)
        .contains("'20000', 1000, 200000")
        .contains("'2', 1, 10")
        .contains("'200', 50, 1000")
        .contains("'40', 1, 200")
        .contains("'90', 7, 365")
        .contains("'30', 1, 100")
        .contains("'2000', 0, 100000")
        .contains("'3', 2, 50")
        .contains("'60', 0, 1000")
        .contains("ADD COLUMN IF NOT EXISTS default_value", "min_value", "max_value")
        .contains("ck_orazaka_runtime_config_value");
  }
}
