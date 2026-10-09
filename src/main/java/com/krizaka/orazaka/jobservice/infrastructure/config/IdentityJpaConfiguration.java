package com.krizaka.orazaka.jobservice.infrastructure.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA infrastructure configuration for the Orazaka router.
 *
 * <p>Enables cross-module entity scanning and repository activation for all {@code
 * com.krizaka.orazaka.*.entity} and {@code com.krizaka.orazaka.*.repository} packs, allowing the
 * router to access identity, tools, and core entities in a single persistence context.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.krizaka.orazaka") // ◄ Scanne tout le monorepo pour les Repositories
@EntityScan(basePackages = "com.krizaka.orazaka")
public class IdentityJpaConfiguration {}
