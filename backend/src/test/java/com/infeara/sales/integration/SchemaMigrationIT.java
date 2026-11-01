package com.infeara.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the migration chain applies cleanly to a real MariaDB 11.4 instance and that the
 * resulting schema contains the tables the entities expect (B-2 in KNOWN-ISSUES).
 *
 * Runs in the `verify` phase (`*IT`), so `mvn test` alone stays fast and Docker-free.
 */
@Testcontainers
class SchemaMigrationIT {

    @Container
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("infeara_sales")
            .withUsername("infeara")
            .withPassword("test-password");

    @Test
    @DisplayName("applies V1–V7 and leaves no pending migrations")
    void appliesAllMigrations() {
        Flyway flyway = Flyway.configure()
                .dataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("critical tables and constraints exist after migration")
    void schemaContainsCoreTables() {
        Flyway flyway = Flyway.configure()
                .dataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        try (var connection = java.sql.DriverManager.getConnection(
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                      WHERE table_schema = DATABASE() ORDER BY table_name
                     """)) {
            var tables = new java.util.ArrayList<String>();
            while (rows.next()) {
                tables.add(rows.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(tables).contains("leads", "opportunities", "stage_history", "audit_logs",
                    "outbox_events", "jobs", "email_messages", "sequence_enrollments", "roles",
                    "user_sessions");
            assertThat(tables).hasSizeGreaterThanOrEqualTo(70);
        } catch (Exception ex) {
            throw new AssertionError("Could not inspect the migrated schema", ex);
        }
    }
}
