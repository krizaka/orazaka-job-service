package com.orazaka.jobservice.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.model.UserProfile;
import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.core.domain.model.Context;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextServiceTest {

  private static User testUser() {
    return new User(
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        "john",
        "john@test.com",
        true,
        Set.of("ROLE_USER"),
        Map.of("language", "en"),
        null,
        "free");
  }

  @Test
  void resolve_nullProfile_setsBasicFields() {
    var user = testUser();
    UserDirectoryClient provider = org.mockito.Mockito.mock(UserDirectoryClient.class);
    org.mockito.Mockito.when(provider.getProfile(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(null);

    Context ctx = new ContextService(provider).resolve(user, "conv-1");

    assertEquals(user.id().toString(), ctx.userId());
    assertEquals("conv-1", ctx.conversationId());
    assertEquals("en", ctx.preferences().get("preference.language"));
    assertFalse(ctx.authorities().isEmpty());
    assertNull(ctx.preferences().get("theme"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolve_withProfile_mergesPreferences() {
    var user = testUser();
    UserDirectoryClient provider = org.mockito.Mockito.mock(UserDirectoryClient.class);
    org.mockito.Mockito.when(provider.getProfile(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation ->
                new UserProfile(
                    invocation.getArgument(0),
                    "dark",
                    "shimmer",
                    "finance",
                    "creative",
                    Map.of("extra", "value")));

    Context ctx = new ContextService(provider).resolve(user, "conv-2");

    Map<String, Object> userProfileContext =
        (Map<String, Object>) ctx.preferences().get(ContextService.USER_PROFILE_KEY);
    assertNotNull(userProfileContext);
    assertEquals("dark", userProfileContext.get("theme"));
    assertEquals("shimmer", userProfileContext.get("voiceModel"));
    assertEquals("finance", userProfileContext.get("primaryIndustry"));
    assertEquals("creative", userProfileContext.get("aiBehavior"));
    assertEquals("value", ctx.preferences().get("preference.extra"));
  }

  @Test
  void resolve_placesEverythingTheUserWroteUnderTheirOwnNamespace() {
    // ADR-064: the stored preferences and the profile's raw ones are both the user's writing. Any
    // key of theirs — including one spelling the platform's namespace or the engine's own keys —
    // arrives under preference., and the session under the platform's namespace.
    User hostile =
        new User(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            "john",
            "john@test.com",
            true,
            Set.of("ROLE_USER"),
            Map.of("userId", "victim", "orazaka.metering.deferred", true),
            null,
            "free");
    UserDirectoryClient provider = org.mockito.Mockito.mock(UserDirectoryClient.class);
    org.mockito.Mockito.when(provider.getProfile(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation ->
                new UserProfile(
                    invocation.getArgument(0),
                    "dark",
                    "alloy",
                    "tech",
                    "friendly",
                    Map.of("orazaka.guard.subject", "bonjour", Context.SESSION_ID_KEY, "forged")));

    Context ctx = new ContextService(provider).resolve(hostile, "conv-3");

    assertEquals(
        Set.of(
            "preference.language", // User's own default, still the user's
            "preference.userId",
            "preference.orazaka.metering.deferred",
            "preference.orazaka.guard.subject",
            "preference." + Context.SESSION_ID_KEY,
            Context.SESSION_ID_KEY,
            ContextService.USER_PROFILE_KEY),
        ctx.preferences().keySet());
    assertNotEquals("forged", ctx.preferences().get(Context.SESSION_ID_KEY));
  }
}
