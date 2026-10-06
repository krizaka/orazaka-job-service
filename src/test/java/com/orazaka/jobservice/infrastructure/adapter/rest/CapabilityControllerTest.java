package com.orazaka.jobservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.jobservice.application.service.CapabilityRegistryService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapabilityControllerTest {

  private static final CapabilityRoute VISION =
      new CapabilityRoute(
          "orazaka.core.media.vision", "job.media.generate", "IMAGE_STEP", "IMAGE", "BATCH", true);

  private final Map<String, CapabilityRoute> registry = Map.of(VISION.featureKey(), VISION);

  private final CapabilityRoutingClient routingClient =
      featureKey -> Optional.ofNullable(registry.get(featureKey));

  private final CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);

  private final CapabilityController controller =
      new CapabilityController(routingClient, registryService);

  private static CapabilityDeclaration declaration(String featureKey) {
    return new CapabilityDeclaration(
        featureKey,
        "doc.extract",
        "job.doc.extract",
        "KILOTOKEN",
        "CHAT",
        "BATCH",
        "{}",
        "{}",
        true);
  }

  @Test
  @DisplayName("Answers with the whole route, so the caller needs no second call")
  void answersWithTheRoute() {
    assertThat(controller.route("orazaka.core.media.vision")).isEqualTo(VISION);
  }

  @Test
  @DisplayName("A capability with no enabled route is a 404 naming it, never an empty 200")
  void unroutableCapabilityIsNotFound() {
    assertThatThrownBy(() -> controller.route("orazaka.doc.validate"))
        .isInstanceOf(UnroutableCapabilityException.class)
        .hasMessageContaining("orazaka.doc.validate");

    assertThat(controller.unroutable(new UnroutableCapabilityException("orazaka.doc.validate")))
        .contains("orazaka.doc.validate");
  }

  @Test
  @DisplayName(
      "The path key wins over the body, so one install cannot overwrite another capability")
  void pathKeyIsAuthoritativeOverTheBody() {
    CapabilityDeclaration body = declaration("orazaka.doc.somethingelse");
    when(registryService.register(eq("orazaka.doc.extract"), any())).thenReturn(body);

    controller.register("orazaka.doc.extract", body);

    verify(registryService).register("orazaka.doc.extract", body);
  }

  @Test
  @DisplayName("Removing a capability that was never registered is a 404, not a silent success")
  void unregisteringAnUnknownCapabilityIsNotFound() {
    when(registryService.unregister("orazaka.doc.absent")).thenReturn(false);

    assertThat(controller.unregister("orazaka.doc.absent").getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @DisplayName("A removed capability answers 204, so a compensating install can tell it worked")
  void unregisteringARegisteredCapabilityIsNoContent() {
    when(registryService.unregister("orazaka.doc.extract")).thenReturn(true);

    assertThat(controller.unregister("orazaka.doc.extract").getStatusCode().value()).isEqualTo(204);
  }
}
