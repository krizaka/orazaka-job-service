package com.krizaka.orazaka.jobservice.catalog;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link QueueConsumers} read from the worker registry ({@code worker_registry}, kept by {@code
 * WorkerRegistryService}) and from the kinds of job an external worker runs ({@code
 * model_category_queue}, data: one row per category and per hosted / self-hosted).
 *
 * <p>Only a kind listed there is ever greyed out by this: the job service runs the others itself,
 * and the registry is advisory (ADR-038) — a worker that failed to register must not switch off
 * what it can serve. A hosted model of a category that has no hosted row waits where the
 * self-hosted ones do. A table that is not readable (a database not migrated yet) means nothing is
 * known, so nothing is greyed out.
 */
@Component
class RegistryQueueConsumers implements QueueConsumers {

  private static final Logger logger = LoggerFactory.getLogger(RegistryQueueConsumers.class);

  private static final Pattern BINDING = Pattern.compile("\"(job\\.[^\"]+)\"");

  private final JdbcTemplate jdbcTemplate;

  RegistryQueueConsumers(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public boolean drained(String category, boolean hosted) {
    try {
      return queueOf(category, hosted).map(this::drains).orElse(true);
    } catch (DataAccessException unknown) {
      logger.debug("The worker registry is not readable: {}", unknown.toString());
      return true;
    }
  }

  @Override
  public Optional<String> queueOf(String category, boolean hosted) {
    try {
      Optional<String> own = rowOf(category, hosted);
      return own.isPresent() || !hosted ? own : rowOf(category, false);
    } catch (DataAccessException unknown) {
      logger.debug("model_category_queue is not readable: {}", unknown.toString());
      return Optional.empty();
    }
  }

  private Optional<String> rowOf(String category, boolean hosted) {
    return jdbcTemplate
        .queryForList(
            "SELECT routing_key FROM model_category_queue WHERE category = ? AND hosted = ?",
            String.class,
            category,
            hosted)
        .stream()
        .findFirst();
  }

  /**
   * Whether some {@code HEALTHY} worker is bound to the key the way the broker would deliver it.
   */
  boolean drains(String routingKey) {
    for (String bindings :
        jdbcTemplate.queryForList(
            "SELECT bindings::text FROM worker_registry WHERE status = 'HEALTHY'", String.class)) {
      Matcher binding = BINDING.matcher(bindings);
      while (binding.find()) {
        if (RoutingKeys.matches(binding.group(1), routingKey)) {
          return true;
        }
      }
    }
    return false;
  }
}
