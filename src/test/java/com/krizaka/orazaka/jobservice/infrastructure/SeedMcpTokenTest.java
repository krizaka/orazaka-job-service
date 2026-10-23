package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP server tokens are stored sealed (orazaka-ai-engine#58): the envelope of a long token is
 * longer than the token, so the column is TEXT, not VARCHAR(1000).
 */
class SeedMcpTokenTest {

  @Test
  @DisplayName("both MCP token columns hold a sealed envelope of any length")
  void tokenColumnsAreText() throws Exception {
    String seed = Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    for (String table : new String[] {"user_mcp_servers", "platform_mcp_servers"}) {
      String body = seed.substring(seed.indexOf("CREATE TABLE " + table + " ("));
      body = body.substring(0, body.indexOf(");"));
      assertThat(body).as(table).contains("auth_token TEXT").doesNotContain("auth_token VARCHAR");
    }
  }
}
