package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import com.orazaka.jobs.domain.model.FailureCause;
import com.orazaka.persistence.infrastructure.config.MessagingContract;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class JobEventPublisherTest {

  @Mock private RabbitTemplate rabbitTemplate;

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturePayload(String expectedRoutingKey) {
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(rabbitTemplate)
        .convertAndSend(
            org.mockito.ArgumentMatchers.eq(MessagingContract.EVENTS_EXCHANGE),
            org.mockito.ArgumentMatchers.eq(expectedRoutingKey),
            payload.capture());
    return (Map<String, Object>) payload.getValue();
  }

  @Test
  void constructor_nullTemplate_throws() {
    assertThrows(NullPointerException.class, () -> new JobEventPublisher(null));
  }

  @Test
  void emitDone_carriesTheReservation_soBillingCanSettleIt() {
    new JobEventPublisher(rabbitTemplate)
        .emitDone(
            "job-1",
            "hold-7",
            "sdxl-turbo",
            Map.of("url", "/out.mp4"),
            Map.of("frames", 48, "fps", 12));

    Map<String, Object> payload = capturePayload("job.job-1.done");
    assertEquals("job-1", payload.get("jobId"));
    assertEquals("hold-7", payload.get("holdId"));
    assertEquals(Map.of("url", "/out.mp4"), payload.get("result"));
    assertEquals(Map.of("frames", 48, "fps", 12), payload.get("consumption"));
    assertEquals("sdxl-turbo", payload.get("model"));
  }

  @Test
  void emitDone_omitsTheModel_whenTheProducerNamedNone() {
    // Absent, not "default": billing falls back to the capability's own pricebook row on a null
    // model, and a sentinel string would look for a row named "default" and find none.
    new JobEventPublisher(rabbitTemplate).emitDone("job-5", "hold-9", null, Map.of(), Map.of());

    assertFalse(capturePayload("job.job-5.done").containsKey("model"));
  }

  @Test
  void emitError_carriesTheReservation_soBillingReleasesRatherThanBills() {
    new JobEventPublisher(rabbitTemplate)
        .emitError("job-2", "hold-8", FailureCause.EXECUTOR_FAULT, "MLX out of memory");

    Map<String, Object> payload = capturePayload("job.job-2.error");
    assertEquals("hold-8", payload.get("holdId"));
    assertEquals("MLX out of memory", payload.get("error"));
    assertEquals("EXECUTOR_FAULT", payload.get("cause"));
  }

  @Test
  void unmeteredJob_omitsTheKeyEntirely_soAbsenceMeansNeverAuthorised() {
    new JobEventPublisher(rabbitTemplate).emitDone("job-3", null, "sdxl-turbo", Map.of(), Map.of());

    assertFalse(capturePayload("job.job-3.done").containsKey("holdId"));
  }

  @Test
  void blankHoldId_isTreatedAsUnmetered() {
    new JobEventPublisher(rabbitTemplate).emitError("job-4", "  ", FailureCause.TIMEOUT, "boom");

    assertFalse(capturePayload("job.job-4.error").containsKey("holdId"));
  }

  @Test
  void nullResult_becomesAnEmptyMap_ratherThanANullOnTheWire() {
    new JobEventPublisher(rabbitTemplate).emitDone("job-6", "hold-9", "m", null, null);

    assertEquals(Map.of(), capturePayload("job.job-6.done").get("result"));
  }

  @Test
  void emptyConsumption_omitsTheKey_soAnUnmeasuredJobIsReleasedNotBilled() {
    new JobEventPublisher(rabbitTemplate).emitDone("job-7", "hold-11", "m", Map.of(), Map.of());

    assertFalse(capturePayload("job.job-7.done").containsKey("consumption"));
  }

  @Test
  void nullError_getsADefaultReason() {
    new JobEventPublisher(rabbitTemplate)
        .emitError("job-6", "hold-10", FailureCause.GUARD_REFUSAL, null);

    assertEquals("Job failed", capturePayload("job.job-6.error").get("error"));
  }

  @Test
  void everyErrorCarriesACause_andSilenceIsAnExecutorFault() {
    // The wire contract of ADR-053: a consumer must never have to read `error` to learn the
    // category, and a producer that passes nothing must not be read as a refusal.
    new JobEventPublisher(rabbitTemplate).emitError("job-8", "hold-1", null, "something");

    assertEquals("EXECUTOR_FAULT", capturePayload("job.job-8.error").get("cause"));
  }

  @Test
  void theCauseTravelsAsItsName_notAsAnOrdinal() {
    // An ordinal on the wire would silently re-map every consumer the day a cause is inserted.
    new JobEventPublisher(rabbitTemplate)
        .emitError("job-9", "hold-2", FailureCause.PLATFORM_UNAVAILABLE, "ollama is down");

    assertEquals("PLATFORM_UNAVAILABLE", capturePayload("job.job-9.error").get("cause"));
  }
}
