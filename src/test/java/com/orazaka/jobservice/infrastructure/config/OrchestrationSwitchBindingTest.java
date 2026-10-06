package com.orazaka.jobservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.core.infrastructure.config.CoreConfiguration;
import com.orazaka.core.infrastructure.config.CoreProperties;
import com.orazaka.test.config.ApplicationYaml;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-062 — this service's orchestration switch, bound from its real {@code application.yml}.
 *
 * <p>Not a unit test over a hand-built {@code OrchestrationConfig}: one of those would have passed
 * before the fix too. The defect was that the binder built no {@code OrchestrationConfig} from this
 * yaml at all, so {@code false} never reached the bean. Only binding the real file can see that.
 */
class OrchestrationSwitchBindingTest {

  private static CoreProperties bound(Map<String, Object> overrides) {
    return new CoreConfiguration().coreProperties(ApplicationYaml.ofThisService(overrides));
  }

  @Test
  @DisplayName(
      "[ADR-062] ORCHESTRATION_ENABLED=false, the variable this yaml names, reaches the bean as false")
  void theEnvironmentVariableTheYamlNamesSwitchesItOff() {
    assertThat(bound(Map.of("ORCHESTRATION_ENABLED", "false")).orchestration().enabled()).isFalse();
  }

  @Test
  @DisplayName("[ADR-062] the property itself, set above the yaml, reaches the bean as false")
  void thePropertySwitchesItOff() {
    assertThat(
            bound(Map.of("orazaka.core.orchestration.enabled", "false")).orchestration().enabled())
        .isFalse();
  }

  @Test
  @DisplayName("[ADR-062] unset, the yaml's own default keeps the engine on")
  void unsetTheYamlDefaultKeepsItOn() {
    assertThat(bound(Map.of()).orchestration().enabled()).isTrue();
  }
}
