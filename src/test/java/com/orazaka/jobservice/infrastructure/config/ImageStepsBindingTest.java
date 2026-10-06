package com.orazaka.jobservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.core.infrastructure.config.CoreConfiguration;
import com.orazaka.core.infrastructure.config.CoreProperties;
import com.orazaka.test.config.ApplicationYaml;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

/**
 * ADR-062 — the IMAGE_STEP meter reads the step count this service's yaml declares.
 *
 * <p>Two causes kept it at 20, and they are tested apart because "two independent causes" is
 * exactly the shape where the visible one is fixed and victory is declared. Cause 1: a second
 * constructor on {@code ImageGenerationConfig}, so the binder built nothing. Cause 2: {@code
 * CoreConfiguration} never read the bound image config at all and built one from literals. The
 * first test fails on cause 1 only. The second fails on either — and after cause 1 alone was fixed
 * it was the only one still red, which is the evidence that the visible fix did not fix the bill.
 */
class ImageStepsBindingTest {

  private static StandardEnvironment yaml(Map<String, Object> overrides) {
    return ApplicationYaml.ofThisService(overrides);
  }

  @Test
  @DisplayName(
      "[ADR-062 cause 1] the binder builds this yaml's image config — IMAGE_GEN_STEPS=10 binds 10")
  void theBinderReadsTheDeploymentsSteps() {
    CoreProperties bound =
        Binder.get(yaml(Map.of("IMAGE_GEN_STEPS", "10")))
            .bind("orazaka.core", CoreProperties.class)
            .orElseThrow(() -> new AssertionError("orazaka.core did not bind"));

    assertThat(bound.image()).as("cause 1: the binder built no image config").isNotNull();
    assertThat(bound.image().generation().steps()).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "[ADR-062 cause 2] the bean the meter reads carries IMAGE_GEN_STEPS=10, not a literal 20")
  void theMetersBeanCarriesTheDeploymentsSteps() {
    CoreProperties bean =
        new CoreConfiguration().coreProperties(yaml(Map.of("IMAGE_GEN_STEPS", "10")));

    assertThat(bean.image().generation().steps())
        .as("cause 2: CoreConfiguration built the image config from literals")
        .isEqualTo(10);
  }

  @Test
  @DisplayName("[ADR-062] unset, the yaml's default of 20 is what is billed")
  void unsetTheYamlDefaultApplies() {
    assertThat(new CoreConfiguration().coreProperties(yaml(Map.of())).image().generation().steps())
        .isEqualTo(20);
  }
}
