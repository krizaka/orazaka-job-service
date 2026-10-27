package com.krizaka.orazaka.jobservice.application.service;

import java.io.Serial;

/**
 * A capability registration the platform refuses because its content is invalid.
 *
 * <p>A client error, rendered as 422 by the controller: before this, an empty or unknown {@code
 * billableCapability} reached the table's NOT NULL / CHECK constraint and came back as a 500, which
 * read as "the job service is broken" when the pack was (orazaka-packs#162).
 */
public class InvalidCapabilityDeclarationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidCapabilityDeclarationException(String message) {
    super(message);
  }
}
