package com.carenexus.api.common.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test configuration that provides a PostgreSQL container for integration tests.
 * Automatically registered via Spring Boot's test container support.
 */
@TestConfiguration
public class PostgresTestContainerConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("carenexus_test")
                .withUsername("carenexus")
                .withPassword("test_password")
                .withInitScript("init-test-db.sql");
    }
}
