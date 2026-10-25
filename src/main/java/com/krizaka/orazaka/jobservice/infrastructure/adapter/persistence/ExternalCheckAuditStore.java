package com.krizaka.orazaka.jobservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.core.domain.model.checks.CheckAudit;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckAuditLog;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The external-check audit in {@code external_check_audit} (30-jobs-config.sql): one row per call,
 * refused or not, appended and never rewritten (the table's trigger refuses an update).
 *
 * <p>A table rather than a log line: the audit is the proof that no provider was asked without
 * consent, a key and a hold (Loi 25), and it has to outlive a log's rotation and be queryable by
 * run and actor. It holds no personal data of the subject — the engine names the subject by a keyed
 * HMAC of its opaque id, and the provider reference is the provider's opaque case id.
 *
 * <p>A failure to write is not swallowed: the check's job fails, so an unaudited call is never
 * reported as done.
 */
public final class ExternalCheckAuditStore implements CheckAuditLog {

  private static final String APPEND =
      "INSERT INTO external_check_audit (at, actor_id, run_id, provider, purpose, key_source,"
          + " outcome, refusal, subject_ref, duration_ms, provider_ref)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;

  /**
   * @param jdbcTemplate this service's database
   */
  public ExternalCheckAuditStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public void record(CheckAudit line) {
    jdbcTemplate.update(
        APPEND,
        Timestamp.from(line.at()),
        line.actorId(),
        line.runId(),
        line.provider(),
        line.purpose().name(),
        line.keySource() == null ? null : line.keySource().name(),
        line.outcome().name(),
        line.refusal() == null ? null : line.refusal().name(),
        line.subjectRef(),
        line.durationMs(),
        line.providerRef());
  }
}
