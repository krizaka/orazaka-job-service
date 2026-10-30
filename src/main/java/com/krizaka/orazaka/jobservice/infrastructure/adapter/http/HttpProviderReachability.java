package com.krizaka.orazaka.jobservice.infrastructure.adapter.http;

import com.krizaka.orazaka.jobservice.catalog.ProviderReachability;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Probes a provider's base URL: an HTTP answer below 500 means the engine is up; a refused or
 * timed-out connection, or a 5xx (a relay that is up while the engine behind it is stopped answers
 * 502/503/504), means it is not.
 *
 * <p>Cached, because the answer is read for every option of every form and on every run: an answer
 * lives {@value #UP_TTL_SECONDS} s, a failure {@value #DOWN_TTL_SECONDS} s so a server that just
 * started is noticed quickly and a dead one does not cost a timeout per request.
 */
@Component
class HttpProviderReachability implements ProviderReachability {

  private static final Logger logger = LoggerFactory.getLogger(HttpProviderReachability.class);

  private static final int SERVER_ERROR = 500;
  private static final long UP_TTL_SECONDS = 30;
  private static final long DOWN_TTL_SECONDS = 10;
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

  private final CatalogModelManager catalogModelManager;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(PROBE_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ConcurrentHashMap<String, Probe> cache = new ConcurrentHashMap<>();

  private record Probe(boolean up, long expiresAtMillis) {}

  HttpProviderReachability(CatalogModelManager catalogModelManager) {
    this.catalogModelManager =
        Objects.requireNonNull(catalogModelManager, "CatalogModelManager cannot be null");
  }

  @Override
  public boolean reachable(String providerName) {
    if (providerName == null || providerName.isBlank()) {
      return false;
    }
    long now = System.currentTimeMillis();
    Probe cached = cache.get(providerName);
    if (cached != null && cached.expiresAtMillis() > now) {
      return cached.up();
    }
    boolean up = probe(providerName);
    long ttl = up ? UP_TTL_SECONDS : DOWN_TTL_SECONDS;
    cache.put(providerName, new Probe(up, now + ttl * 1000));
    return up;
  }

  private record Loaded(java.util.Optional<java.util.Set<String>> ids, long expiresAtMillis) {}

  private final ConcurrentHashMap<String, Loaded> loaded = new ConcurrentHashMap<>();

  /**
   * The only id stable-diffusion.cpp's sd-server ever lists, whatever model it was started with: it
   * names nothing, so it is the same as an engine that lists nothing (orazaka#508: every model it
   * served was reported "not running").
   */
  private static final String SD_SERVER_PLACEHOLDER_ID = "sd-cpp-local";

  private static final java.util.regex.Pattern MODEL_ID =
      java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

  @Override
  public boolean serves(String providerName, String modelName) {
    if (!reachable(providerName)) {
      return false;
    }
    if (modelName == null || modelName.isBlank()) {
      return true;
    }
    long now = System.currentTimeMillis();
    Loaded cached = loaded.get(providerName);
    if (cached == null || cached.expiresAtMillis() <= now) {
      cached = new Loaded(listModels(providerName), now + UP_TTL_SECONDS * 1000);
      loaded.put(providerName, cached);
    }
    return cached.ids().map(ids -> ids.contains(modelName.strip().toLowerCase())).orElse(true);
  }

  /** The ids the engine says it has loaded; empty when it does not say (then it is trusted). */
  private java.util.Optional<java.util.Set<String>> listModels(String providerName) {
    String baseUrl = catalogModelManager.getProviderBaseUrl(providerName);
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String url = trimmed.endsWith("/v1") ? trimmed + "/models" : trimmed + "/v1/models";
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return java.util.Optional.empty();
      }
      java.util.Set<String> ids = new java.util.HashSet<>();
      java.util.regex.Matcher matcher = MODEL_ID.matcher(response.body());
      while (matcher.find()) {
        ids.add(matcher.group(1).toLowerCase());
      }
      ids.remove(SD_SERVER_PLACEHOLDER_ID);
      return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids);
    } catch (java.io.IOException | IllegalArgumentException unreadable) {
      return java.util.Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return java.util.Optional.empty();
    }
  }

  private boolean probe(String providerName) {
    String baseUrl = catalogModelManager.getProviderBaseUrl(providerName);
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl)).timeout(PROBE_TIMEOUT).GET().build();
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status < SERVER_ERROR;
    } catch (java.io.IOException | IllegalArgumentException unreachable) {
      logger.debug(
          "Provider {} at {} does not answer: {}", providerName, baseUrl, unreachable.toString());
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
