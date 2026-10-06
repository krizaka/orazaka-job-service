package com.orazaka.jobservice.infrastructure.adapter.amqp;

import com.orazaka.core.domain.model.Authority;
import com.orazaka.core.domain.model.Context;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps the Tier-1 execution context onto the engine's own {@code Context}.
 *
 * <p>The anti-corruption layer between the executor contract and the engine ([ERR-127]). The port
 * cannot carry {@code Context} — that would make Tier-1 depend on {@code orazaka-core} and put the
 * engine on the classpath of every out-of-tree executor (ADR-038). The in-process executors that
 * call {@code AiClient} still need one, so the translation happens here, once, at the boundary
 * rather than four times at the call sites.
 *
 * <p>{@code final}, static, package-private, dependency-free — the sanctioned {@code *Mapper} shape
 * of [ERR-107], not a {@code *Util}.
 */
final class ExecutionContextMapper {

  private ExecutionContextMapper() {}

  /**
   * @param context the Tier-1 context the listener resolved
   * @return the engine context an {@code AiClient} request expects
   */
  static Context toEngineContext(JobExecutionContext context) {
    Set<Authority> authorities =
        context.authorities().stream().map(Authority::new).collect(Collectors.toSet());
    return new Context(
        context.actorId(), context.correlationId(), context.preferences(), authorities);
  }
}
