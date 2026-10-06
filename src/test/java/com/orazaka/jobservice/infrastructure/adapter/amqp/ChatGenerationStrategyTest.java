package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.orazaka.core.domain.model.chat.ChatRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.core.domain.model.chat.TokenUsage;
import com.orazaka.core.domain.ports.inbound.AiClient;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatGenerationStrategyTest {

  @Mock private AiClient aiClient;

  private ChatGenerationStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new ChatGenerationStrategy(aiClient);
  }

  @Test
  void handlerKey_isTextGenerate() {
    assertEquals("text.generate", strategy.handlerKey());
  }

  @Test
  void execute_successfulChatWithPrompt_returnsResponseContent() throws Exception {
    JobCommand message =
        new JobCommand("job-1", "user-1", "chat", Map.of("prompt", "What is Java?"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse response =
        new ChatResponse(
            "Java is a programming language",
            "conv-1",
            TokenUsage.reported(400, 3000, 3400),
            Map.of("tokens", 100));
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("Java is a programming language", result.get("content"));
    assertEquals(Map.of("tokens", 100), result.get("metadata"));

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    assertEquals("What is Java?", captor.getValue().prompt());
  }

  @Test
  void execute_successfulChatWithText_returnsResponseContent() throws Exception {
    JobCommand message =
        new JobCommand("job-1", "user-1", "chat", Map.of("text", "Tell me a joke"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse response =
        new ChatResponse("Why did the chicken cross the road?", "conv-1", TokenUsage.none(), null);
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("Why did the chicken cross the road?", result.get("content"));
    assertNull(result.get("metadata"));

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    assertEquals("Tell me a joke", captor.getValue().prompt());
  }

  @Test
  void execute_missingPromptAndText_throwsIllegalArgumentException() {
    JobCommand message = new JobCommand("job-1", "user-1", "chat", Map.of());
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(IllegalArgumentException.class, () -> strategy.execute(message, context));
  }

  @Test
  @DisplayName("[ADR-041] a chat turn reports the tokens it consumed, so a run can be billed")
  void reportsTheTokensItConsumed() throws Exception {
    JobCommand message =
        new JobCommand(
            "job-1", "test-user", "orazaka.core.chat.completion", Map.of("prompt", "hi"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(aiClient.chat(any(ChatRequest.class)))
        .thenReturn(
            new ChatResponse("hello", "conv-1", TokenUsage.reported(400, 3000, 3400), null));

    JobExecutionResult result = strategy.execute(message, context);

    // A MEASUREMENT, never a unit or a price: the ledger derives KILOTOKEN from the pinned rate.
    assertEquals(3400, result.consumption().get("tokens"));
  }

  @Test
  @DisplayName("An unreported turn measures nothing — released, never billed at an estimate")
  void unreportedUsageMeasuresNothing() throws Exception {
    JobCommand message =
        new JobCommand(
            "job-2", "test-user", "orazaka.core.chat.completion", Map.of("prompt", "hi"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(aiClient.chat(any(ChatRequest.class)))
        .thenReturn(new ChatResponse("hello", "conv-1", TokenUsage.none(), null));

    assertTrue(strategy.execute(message, context).consumption().isEmpty());
  }
}
