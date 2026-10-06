package com.orazaka.jobservice.infrastructure.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.UnmeteredTurn;
import com.orazaka.persistence.domain.model.OutboxMessage;
import com.orazaka.persistence.domain.ports.inbound.OutboxStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxUnmeteredTurnRepositoryAdapterTest {

  private final OutboxStore outbox = mock(OutboxStore.class);
  private final UnmeteredTurn turn =
      new UnmeteredTurn(
          "actor",
          BillableCapability.CHAT,
          "conv-64",
          "billing unreachable: RestClientException",
          Instant.parse("2026-09-15T12:00:00Z"));

  @Test
  void theTurnIsAppendedToTheOutboxOnTheEventsExchange() {
    new OutboxUnmeteredTurnRepositoryAdapter(outbox).record(turn);

    ArgumentCaptor<OutboxMessage> appended = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outbox).append(appended.capture());
    assertEquals("orazaka.events", appended.getValue().exchange());
    assertEquals(UnmeteredTurn.ROUTING_KEY, appended.getValue().routingKey());
    assertEquals("conv-64", appended.getValue().aggregateId());
    assertSame(turn, appended.getValue().payload());
  }

  @Test
  void aTurnTheOutboxCannotHoldIsNotReportedAsRecorded() {
    doThrow(new IllegalStateException("database unavailable")).when(outbox).append(any());

    assertThrows(
        IllegalStateException.class,
        () -> new OutboxUnmeteredTurnRepositoryAdapter(outbox).record(turn));
  }
}
