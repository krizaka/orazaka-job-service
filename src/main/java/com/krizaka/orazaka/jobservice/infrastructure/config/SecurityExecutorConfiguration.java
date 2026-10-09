package com.krizaka.orazaka.jobservice.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

/**
 * Global configuration to bridge SecurityContext from web ingress threads to Virtual Thread pools
 * for async CompletableFuture tasks.
 */
@Configuration
public class SecurityExecutorConfiguration {

  @Bean
  public ExecutorService virtualThreadExecutor() {
    ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    return new DelegatingSecurityContextExecutorService(delegate);
  }
}
