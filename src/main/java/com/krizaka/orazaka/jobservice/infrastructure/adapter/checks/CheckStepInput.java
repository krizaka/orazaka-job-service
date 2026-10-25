package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.model.checks.CheckSubject;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp.InvalidJobInputException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The inputs of an external-check step, as the contract of {@code orazaka-packs}' {@code
 * tests/contracts/orazaka-core-checks.json} declares them: a {@code subject} object and the {@code
 * consentedPurposes}.
 *
 * <p>A purpose is granted only when it is listed: no list, an empty list or a list of other
 * purposes ({@code DOCUMENTS}) grants no check. A purpose name this engine does not know grants
 * nothing and is not an error, because the same list serves the pack's other stages.
 *
 * <p>Every refusal names the input and what is wrong with it, never its value: the subject is
 * personal data, and the message is stored on the job and published with its error.
 *
 * @param subject the person checked
 * @param consented the check purposes the subject consented to
 */
record CheckStepInput(CheckSubject subject, Set<CheckPurpose> consented) {

  /** Copies the purposes. */
  CheckStepInput {
    Objects.requireNonNull(subject, "subject must not be null");
    consented = Set.copyOf(consented);
  }

  /**
   * Reads the step's inputs out of a job payload.
   *
   * @param payload the job payload
   * @return the inputs
   * @throws InvalidJobInputException when an input does not match the contract
   */
  static CheckStepInput parse(Map<String, Object> payload) throws InvalidJobInputException {
    if (!(payload.get("subject") instanceof Map<?, ?> subject)) {
      throw new InvalidJobInputException(
          "subject must be an object with subjectId, lastName and firstName");
    }
    CheckSubject checked =
        new CheckSubject(
            requiredText(subject, "subjectId"),
            requiredText(subject, "lastName"),
            requiredText(subject, "firstName"),
            birthDate(subject.get("birthDate")),
            optionalText(subject, "address"));
    return new CheckStepInput(checked, purposes(payload.get("consentedPurposes")));
  }

  private static String requiredText(Map<?, ?> subject, String field)
      throws InvalidJobInputException {
    if (!(subject.get(field) instanceof String text) || text.isBlank()) {
      throw new InvalidJobInputException("subject." + field + " is required and must be text");
    }
    return text;
  }

  private static String optionalText(Map<?, ?> subject, String field)
      throws InvalidJobInputException {
    Object value = subject.get(field);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new InvalidJobInputException("subject." + field + " must be text");
    }
    return text.isBlank() ? null : text;
  }

  private static LocalDate birthDate(Object value) throws InvalidJobInputException {
    if (value == null || (value instanceof String text && text.isBlank())) {
      return null;
    }
    if (value instanceof String text) {
      try {
        return LocalDate.parse(text);
      } catch (DateTimeParseException notADate) {
        // The parser's message quotes the text: it stays out.
      }
    }
    throw new InvalidJobInputException("subject.birthDate must be an ISO date (yyyy-mm-dd)");
  }

  private static Set<CheckPurpose> purposes(Object value) throws InvalidJobInputException {
    if (value == null) {
      return Set.of();
    }
    if (!(value instanceof List<?> listed)) {
      throw new InvalidJobInputException("consentedPurposes must be a list of purpose names");
    }
    Set<CheckPurpose> granted = EnumSet.noneOf(CheckPurpose.class);
    for (Object name : listed) {
      if (!(name instanceof String purpose)) {
        throw new InvalidJobInputException("consentedPurposes must be a list of purpose names");
      }
      Arrays.stream(CheckPurpose.values())
          .filter(known -> known.name().equals(purpose))
          .forEach(granted::add);
    }
    return granted;
  }
}
