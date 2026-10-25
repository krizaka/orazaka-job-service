package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.ports.outbound.ConsentRegistry;
import java.util.Objects;
import java.util.Set;

/**
 * The consents of one check job: the purposes its {@code consentedPurposes} input lists, for the
 * subject of that job and no other. Nothing else grants a check here — not another job, not a
 * stored consent, not a default. The pack records and proves the consent (its consent stage); the
 * job carries what was given, and the engine refuses without touching the provider when the purpose
 * is not in it.
 */
final class JobConsents implements ConsentRegistry {

  private final String subjectId;
  private final Set<CheckPurpose> granted;

  /**
   * @param subjectId the opaque id of the job's subject
   * @param granted the purposes the job lists
   */
  JobConsents(String subjectId, Set<CheckPurpose> granted) {
    this.subjectId = Objects.requireNonNull(subjectId, "subjectId must not be null");
    this.granted = Set.copyOf(granted);
  }

  @Override
  public boolean isGranted(String subject, CheckPurpose purpose) {
    return subjectId.equals(subject) && granted.contains(purpose);
  }
}
