package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The {@code calls} consumption key (orazaka-job-service#68): a step metered per {@code CALL} (an
 * external check, ADR-087) reports how many provider calls it was charged for. One is settled
 * against the hold's pinned {@code CALL} rate (ADR-033: hold, then settle). Zero (a provider error,
 * a timeout, a missing consent) must cost nothing, and a {@code CALL} unit is priced at one per
 * terminal outcome by the billing service, so a zero report carries no consumption at all: the
 * settlement then releases the hold in full.
 *
 * <p>Pure: the same report always settles the same way, so a redelivered job settles as the first
 * delivery did.
 */
final class CallConsumption {

  /** The consumption key a {@code CALL}-metered step reports. */
  static final String CALLS = "calls";

  private CallConsumption() {}

  /**
   * What to settle for a job's measured consumption.
   *
   * @param consumption what the execution measured
   * @return an empty map when {@code calls} is 0 (the hold is released in full), else the
   *     consumption unchanged
   * @throws IllegalArgumentException when {@code calls} is negative or not a whole number
   */
  static Map<String, Object> toSettle(Map<String, Object> consumption) {
    if (consumption == null || !(consumption.get(CALLS) instanceof Number reported)) {
      return consumption;
    }
    BigDecimal calls = new BigDecimal(reported.toString());
    if (calls.signum() < 0 || calls.stripTrailingZeros().scale() > 0) {
      throw new IllegalArgumentException("calls must be a whole number of at least 0: " + calls);
    }
    return calls.signum() == 0 ? Map.of() : consumption;
  }
}
