package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A job's consents grant its listed purposes, for its subject only. */
class JobConsentsTest {

  private final JobConsents consents = new JobConsents("subject-42", Set.of(CheckPurpose.CREDIT));

  @Test
  @DisplayName("a listed purpose is granted for the job's subject")
  void listed() {
    assertThat(consents.isGranted("subject-42", CheckPurpose.CREDIT)).isTrue();
  }

  @Test
  @DisplayName("a purpose not listed is not granted")
  void notListed() {
    assertThat(consents.isGranted("subject-42", CheckPurpose.COURT_RECORDS)).isFalse();
  }

  @Test
  @DisplayName("another subject is granted nothing")
  void anotherSubject() {
    assertThat(consents.isGranted("subject-43", CheckPurpose.CREDIT)).isFalse();
  }
}
