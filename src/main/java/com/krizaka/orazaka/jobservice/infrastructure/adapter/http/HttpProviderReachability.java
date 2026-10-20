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
