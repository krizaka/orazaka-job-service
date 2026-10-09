package com.krizaka.orazaka.jobservice.infrastructure.adapter.persistence;

import com.krizaka.billing.domain.model.UnmeteredTurn;
import com.krizaka.billing.domain.port.UnmeteredTurnRepository;
import com.krizaka.orazaka.persistence.domain.model.OutboxMessage;
import com.krizaka.orazaka.persistence.domain.ports.inbound.OutboxStore;
import com.krizaka.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Records a turn served without a credit hold in this service's transactional outbox (ADR-064).
 *
 * <p>The outbox, because of when this is called: billing has just failed to answer. The row is
 * committed in this service's own database — which billing's outage does not touch — and the relay
 * publishes it on {@code orazaka.events} whenever the broker takes it, where the billing service
 * collects it into its reconciliation table. {@link OutboxStore#append} throws when the row cannot
 * be written, which is the port's contract: an unrecorded turn is not served.
 */
@Component
class OutboxUnmeteredTurnRepositoryAdapter implements UnmeteredTurnRepository {

  private static final String AGGREGATE = "turn";

  private final OutboxStore outboxStore;

  OutboxUnmeteredTurnRepositoryAdapter(OutboxStore outboxStore) {
    this.outboxStore = Objects.requireNonNull(outboxStore, "OutboxStore cannot be null");
  }

  @Override
  public void record(UnmeteredTurn turn) {
    outboxStore.append(
        new OutboxMessage(
            AGGREGATE,
            turn.correlationId(),
            MessagingContract.EVENTS_EXCHANGE,
            UnmeteredTurn.ROUTING_KEY,
            turn));
  }
}
