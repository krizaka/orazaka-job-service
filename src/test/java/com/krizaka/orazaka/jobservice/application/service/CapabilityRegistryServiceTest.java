package com.krizaka.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import com.krizaka.orazaka.persistence.domain.ports.inbound.OutboxStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilityRegistryServiceTest {

  private final CapabilityManager capabilityManager = mock(CapabilityManager.class);
  private final OutboxStore outboxStore = mock(OutboxStore.class);
  private final CapabilityRegistryService service =
      new CapabilityRegistryService(capabilityManager, outboxStore);

  private static CapabilityDeclaration declaration(String billableCapability, String unit) {
    return new CapabilityDeclaration(
        "orazaka.screening.plan",
        "screening.plan",
        "job.screening.plan",
        unit,
        billableCapability,
        "BATCH",
        "{}",
        "{}",
        true);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "FREE"})
  @DisplayName("An empty or unknown billableCapability is a typed validation error, not a 500")
  void refusesAnUndeclaredOrUnknownBilling(String billable) {
    assertThatThrownBy(
            () -> service.register("orazaka.screening.plan", declaration(billable, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orazaka.screening.plan")
        .hasMessageContaining("NONE");

    verify(capabilityManager, never()).save(any());
    verify(outboxStore, never()).append(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CHAT", "IMAGE", "AUDIO", "VIDEO", "AGENT", "EXTERNAL_CHECK", "NONE"})
  @DisplayName("Every declared billing is registered, NONE included")
  void registersADeclaredBilling(String billable) {
    CapabilityDeclaration declared = declaration(billable, null);
    when(capabilityManager.save(any())).thenReturn(declared);

    assertThat(service.register("orazaka.screening.plan", declared).billableCapability())
        .isEqualTo(billable);
    verify(outboxStore).append(any());
  }
}
