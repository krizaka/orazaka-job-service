package com.orazaka.jobservice.infrastructure.adapter.amqp;

import com.orazaka.core.domain.model.chat.ChatRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.core.domain.model.chat.TokenUsage;
import com.orazaka.core.domain.ports.inbound.AiClient;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import com.orazaka.jobs.domain.port.JobExecutor;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default fallback strategy for text/chat generation jobs.
 *
 * <p>This strategy handles any feature key that doesn't match a more specific strategy. It has the
 * lowest priority in the strategy chain.
 */
@Component
public final class ChatGenerationStrategy implements JobExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ChatGenerationStrategy.class);

  private final AiClient aiClient;

  public ChatGenerationStrategy(AiClient aiClient) {
    this.aiClient = aiClient;
  }

  @Override
  public String handlerKey() {
    return "text.generate";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    String prompt = message.requirePrompt();

    ChatRequest chatRequest =
        new ChatRequest(prompt, null, null, ExecutionContextMapper.toEngineContext(context));
    ChatResponse chatResponse = aiClient.chat(chatRequest);

    Map<String, Object> result = new HashMap<>();
    result.put("content", chatResponse.content());
    if (chatResponse.metadata() != null) {
      result.put("metadata", chatResponse.metadata());
    }
    // The tokens this turn consumed, reported as a MEASUREMENT — never as a unit or a price. The
    // ledger derives KILOTOKEN from the pinned rate; this executor only says how many tokens ran.
    // Without it an asynchronous chat step reported nothing priceable, so every chat step of every
    // Studio run settled at zero — invisible until a run's steps were priced individually
    // (ADR-041). The synchronous path has measured this all along through ChatCompletedEvent.
    TokenUsage usage = chatResponse.tokenUsage();
    if (!usage.reported()) {
      // Unreported is not zero. Returning no measurement releases the hold instead of billing a
      // guess, which is the same choice ChatSettlementListener makes on the synchronous path.
      logger.warn(
          "Provider reported no token usage for job {}; this step contributes nothing to the run's"
              + " cost rather than being billed at an estimate",
          message.jobId());
      return JobExecutionResult.of(result);
    }
    // No model: this executor does not resolve one — the core's provider mesh picks it — so the
    // CHAT capability's default rate applies, which is the row that prices a chat turn anyway.
    return new JobExecutionResult(result, Map.of("tokens", usage.totalTokens()), null);
  }
}
