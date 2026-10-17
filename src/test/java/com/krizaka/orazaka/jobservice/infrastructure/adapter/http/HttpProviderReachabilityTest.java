package com.krizaka.orazaka.jobservice.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpProviderReachabilityTest {

  private final CatalogModelManager catalog = mock(CatalogModelManager.class);
  private HttpServer server;
  private HttpProviderReachability reachability;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // Even a 404 is an answer: the engine is there. A 502 is a relay whose engine is stopped.
    server.createContext("/", exchange -> exchange.sendResponseHeaders(404, -1));
    server.createContext("/relay", exchange -> exchange.sendResponseHeaders(502, -1));
    server.start();
    reachability = new HttpProviderReachability(catalog);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("A provider that answers with any HTTP status is reachable")
  void answeringProviderIsUp() {
    when(catalog.getProviderBaseUrl("up"))
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

    assertThat(reachability.reachable("up")).isTrue();
  }

  @Test
  @DisplayName("A provider whose port refuses the connection is not reachable")
  void refusingProviderIsDown() {
    int closedPort = server.getAddress().getPort();
    server.stop(0);
    when(catalog.getProviderBaseUrl("down")).thenReturn("http://127.0.0.1:" + closedPort);

    assertThat(reachability.reachable("down")).isFalse();
  }

  @Test
  @DisplayName("A relay that answers 502 because the engine behind it is stopped is not reachable")
  void relayWithStoppedEngineIsDown() {
    when(catalog.getProviderBaseUrl("relay"))
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/relay");

    assertThat(reachability.reachable("relay")).isFalse();
  }

  @Test
  @DisplayName("An unknown provider, a blank one, and a provider without a URL are not reachable")
  void unknownProviderIsDown() {
    assertThat(reachability.reachable("nobody")).isFalse();
    assertThat(reachability.reachable(" ")).isFalse();
    assertThat(reachability.reachable(null)).isFalse();
  }

  @Test
  @DisplayName("The answer is cached, so a form of ten options probes once")
  void answerIsCached() {
    when(catalog.getProviderBaseUrl("up"))
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

    reachability.reachable("up");
    reachability.reachable("up");
    reachability.reachable("up");

    verify(catalog, times(1)).getProviderBaseUrl("up");
  }
}
