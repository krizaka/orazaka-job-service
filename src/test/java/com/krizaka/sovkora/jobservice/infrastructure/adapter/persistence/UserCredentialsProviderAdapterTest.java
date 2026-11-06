package com.krizaka.sovkora.jobservice.infrastructure.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

/** The executor never reads a user's provider key without a grant (GHSA-3pf7-rgf2-4x3m). */
class UserCredentialsProviderAdapterTest {

  private final UserCredentialsProviderAdapter provider = new UserCredentialsProviderAdapter();

  @Test
  void releasesNoKey_forAUserAndAProvider() {
    assertTrue(provider.getDecryptedApiKey("user-1", "openai").isEmpty());
  }

  @Test
  void releasesNoKey_forNullArguments() {
    assertTrue(provider.getDecryptedApiKey(null, "openai").isEmpty());
    assertTrue(provider.getDecryptedApiKey("user-1", null).isEmpty());
  }

  @Test
  void holdsNoIdentityClient_soNothingCanReadAKeyBehindIt() {
    Constructor<?>[] constructors = UserCredentialsProviderAdapter.class.getDeclaredConstructors();
    for (Constructor<?> constructor : constructors) {
      assertTrue(constructor.getParameterCount() == 0);
    }
  }
}
