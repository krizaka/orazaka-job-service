package com.orazaka.jobservice.application.service;

import com.orazaka.jobs.domain.model.WorkerRegistration;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records which workers exist, what they drain, and when each was last heard from.
 *
 * <p><b>Advisory, never authorising.</b> Nothing in dispatch consults this table: a capability's
 * {@code routing_key} alone decides where its job goes (ADR-037). The registry answers a different
 * question — <i>is anything actually draining that key right now?</i> — and phase F turns that into
 * availability a user can see. Making it authoritative would mean a worker that failed to register
 * silently stops serving traffic it is perfectly able to serve, which converts an observability
 * feature into an outage (ADR-038).
 *
 * <p>Registration is an upsert on {@code worker_name}: a restarting worker re-registers rather than
 * accumulating rows, and a version or binding change is picked up without an operator deleting
 * anything.
 *
 * <p>Plain parameterised SQL over the service's own tables, as the studio service does for its own
 * — no JPA entity, which AGENTS.md §5 keeps inside {@code orazaka-persistence}.
 */
@Service
public class WorkerRegistryService {

  private static final Logger logger = LoggerFactory.getLogger(WorkerRegistryService.class);

  private final JdbcTemplate jdbcTemplate;

  public WorkerRegistryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Registers a worker, or refreshes what is already known about it.
   *
   * @param registration what the worker declared in its {@code worker.yaml}
   */
  @Transactional
  public void register(WorkerRegistration registration) {
    jdbcTemplate.update(
        """
        INSERT INTO worker_registry
            (worker_name, worker_family, bindings, version, concurrency, last_seen_at, status)
        VALUES (?, ?, ?::jsonb, ?, ?, now(), 'HEALTHY')
        ON CONFLICT (worker_name) DO UPDATE SET
            worker_family = EXCLUDED.worker_family,
            bindings      = EXCLUDED.bindings,
            version       = EXCLUDED.version,
            concurrency   = EXCLUDED.concurrency,
            last_seen_at  = now(),
            status        = 'HEALTHY'
        """,
        registration.name(),
        registration.family(),
        registration.bindingsAsJson(),
        registration.version(),
        registration.concurrency());
    logger.info(
        "Worker {} registered: family={} bindings={} version={}",
        registration.name(),
        registration.family(),
        registration.bindings(),
        registration.version());
  }

  /**
   * Records that a worker is still alive.
   *
   * @param workerName the worker's declared name
   * @return whether a row was updated — {@code false} means it never registered
   */
  @Transactional
  public boolean heartbeat(String workerName) {
    return jdbcTemplate.update(
            "UPDATE worker_registry SET last_seen_at = now(),"
                + " status = CASE WHEN status = 'DRAINING' THEN status ELSE 'HEALTHY' END"
                + " WHERE worker_name = ?",
            workerName)
        > 0;
  }

  /**
   * Marks every worker unheard-from past the horizon as {@code STALE}.
   *
   * <p>Never deletes: a worker that went away is evidence, and the row is what phase F reads to
   * explain <i>why</i> a capability is unavailable. A deleted row would just look like a worker
   * that never existed.
   *
   * @param horizon how long silence is tolerated before a worker is presumed gone
   * @return how many workers were marked stale by this sweep
   */
  @Transactional
  public int markStaleWorkers(Duration horizon) {
    int marked =
        jdbcTemplate.update(
            "UPDATE worker_registry SET status = 'STALE'"
                + " WHERE status = 'HEALTHY' AND last_seen_at < now() - (? * INTERVAL '1 second')",
            horizon.toSeconds());
    if (marked > 0) {
      logger.warn(
          "Marked {} worker(s) STALE after {}s without a heartbeat", marked, horizon.toSeconds());
    }
    return marked;
  }

  /**
   * @return the families currently served by at least one healthy worker
   */
  @Transactional(readOnly = true)
  public List<String> healthyFamilies() {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT worker_family FROM worker_registry WHERE status = 'HEALTHY'",
        String.class);
  }
}
