package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import java.io.Serial;

/**
 * An executor's declaration that a job's payload is unusable: the one way a failure reads as {@code
 * INPUT_INVALID} (ADR-053). {@link JobListener} never infers it from an exception that merely
 * happened while user data was in scope (ADR-046 §2); an executor throws this only from a validator
 * that checked the payload against its declared input contract, before any paid call.
 *
 * <p>The message is stored on the job and published with its error event, so it names the field and
 * what was wrong with it, never the value: a payload may carry personal data.
 */
public class InvalidJobInputException extends JobExecutionException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param message which input is unusable and why, with no value from the payload
   */
  public InvalidJobInputException(String message) {
    super(message);
  }
}
