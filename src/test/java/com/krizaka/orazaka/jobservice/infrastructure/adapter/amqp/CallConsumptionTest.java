package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallConsumptionTest {

  @Test
  @DisplayName(
      "calls: 0 settles nothing: the consumption is emptied, so billing releases the hold in full")
  void zeroCallsReleasesInFull() {
    assertThat(CallConsumption.toSettle(Map.of("calls", 0, "gpuSeconds", new BigDecimal("0.4"))))
        .isEmpty();
  }

  @Test
  @DisplayName("calls: 1 is settled as reported: one pricebook CALL")
  void oneCallIsSettled() {
    Map<String, Object> reported = Map.of("calls", 1, "gpuSeconds", new BigDecimal("0.4"));

    assertThat(CallConsumption.toSettle(reported)).isEqualTo(reported);
  }

  @Test
  @DisplayName("a consumption without calls is untouched")
  void noCallsKeyIsUntouched() {
    Map<String, Object> reported = Map.of("tokens", 42);

    assertThat(CallConsumption.toSettle(reported)).isEqualTo(reported);
  }

  @Test
  @DisplayName("the same report settles the same way every time: a retry is idempotent")
  void sameReportSameSettlement() {
    Map<String, Object> reported = Map.of("calls", 0);

    assertThat(CallConsumption.toSettle(reported)).isEqualTo(CallConsumption.toSettle(reported));
  }

  @Test
  @DisplayName("a negative or fractional count of calls is refused, never settled")
  void invalidCallsAreRefused() {
    assertThatThrownBy(() -> CallConsumption.toSettle(Map.of("calls", -1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CallConsumption.toSettle(Map.of("calls", new BigDecimal("0.5"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
