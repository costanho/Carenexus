-- Runs once when the Testcontainers PostgreSQL container for the integration tests is created.
--
-- It creates no tables: the schema is built by the real Flyway migrations (classpath:db/migration, no dev seed)
-- when the application context starts, and Hibernate only validates it (ddl-auto: validate), exactly as in
-- production. This script only sets database-level configuration.

-- Set the schema search path
SET search_path TO public;
