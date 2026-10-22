package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The semantic cache (ai-engine#40) is data: a {@code pipeline_interceptor_config} row, shipped
 * OFF, ordered by {@code execution_order} (never an {@code @Order}), and the table its pgvector
 * store reads. Read as text: the database itself is the ITs' business.
 */
class SeedSemanticCacheTest {

  private static final String SEED = read();

  private static String read() {
    try {
      return Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("the cache row exists and is OFF by default, before the router (order 8 < 9)")
  void theRowIsOffByDefault() {
    assertThat(SEED).contains("('SemanticCacheInterceptor','Semantic Cache Interceptor',8, FALSE,");
  }

  @Test
  @DisplayName("the table keeps answers per tenant, scope guard and tier, with an expiry")
  void theTableIsPartitionedAndExpires() {
    String table = SEED.substring(SEED.indexOf("CREATE TABLE IF NOT EXISTS semantic_cache_entry"));
    table = table.substring(0, table.indexOf(");"));
    assertThat(table)
        .contains("tenant_id", "scope_guard_hash", "model_tier", "expires_at", "vector(1024)");
    assertThat(SEED).contains("CREATE EXTENSION IF NOT EXISTS vector;");
  }
}
