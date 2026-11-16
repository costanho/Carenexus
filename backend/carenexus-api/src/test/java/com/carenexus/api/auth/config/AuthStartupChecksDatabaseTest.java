package com.carenexus.api.auth.config;

import com.carenexus.api.auth.service.SystemIdGenerator;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queries behind AuthStartupChecks against the real schema and the real Flyway history: a database migrated
 * with db/migration only passes, one that carries dev seed scripts or active demo accounts is refused.
 */
class AuthStartupChecksDatabaseTest extends CommittedIntegrationTest {

    @Autowired private SystemIdGenerator systemIdGenerator;

    private AuthStartupChecks productionChecks() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new AuthStartupChecks(environment, userRepository, passwordEncoder, systemIdGenerator, jdbc, "", "");
    }

    @Test
    void schemaOnlyDatabaseWithOrdinaryAccounts_passes() {
        assertThatCode(() -> productionChecks().run(null)).doesNotThrowAnyException();
    }

    @Test
    void seedScriptInTheFlywayHistory_isRefused_versionedOrRepeatable() {
        try {
            jdbc.update("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, "
                    + "checksum, installed_by, execution_time, success) "
                    + "VALUES (90001, NULL, 'dev seed 45 referrals', 'SQL', 'R__dev_seed_45_referrals.sql', 1, 'test', 1, TRUE)");
            assertThatThrownBy(() -> productionChecks().run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("R__dev_seed_45_referrals.sql");

            jdbc.update("UPDATE flyway_schema_history SET script = 'V21__dev_seed_thomas_dube_login.sql' "
                    + "WHERE installed_rank = 90001");
            assertThatThrownBy(() -> productionChecks().run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V21__dev_seed_thomas_dube_login.sql");

            // A script that merely resembles a seed name is not one ('_' is not a wildcard here).
            jdbc.update("UPDATE flyway_schema_history SET script = 'RXXdevXseedX45.sql' WHERE installed_rank = 90001");
            assertThatCode(() -> productionChecks().run(null)).doesNotThrowAnyException();
        } finally {
            jdbc.update("DELETE FROM flyway_schema_history WHERE installed_rank = 90001");
        }
    }

    @Test
    void activeAccountWithADemoEmailOrADemoPasswordHash_isRefused_andPassesOnceDeactivated() {
        createAndSaveUser(50, "PAT-00050", "Thomas.Dube@carenexus.com", "PATIENT");
        assertThatThrownBy(() -> productionChecks().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active demo accounts");

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'PAT-00050'");
        assertThatCode(() -> productionChecks().run(null)).doesNotThrowAnyException();

        // Renamed, but still carrying the published V21 password hash.
        jdbc.update("UPDATE users SET is_active = TRUE, email = 'renamed@example.com', "
                + "password_hash = '$2a$10$bSi7sk0xJ9DYnM7StjzsW.TuQSTGqfKzn1bguCJD.g9zb19hpQnbu' WHERE system_id = 'PAT-00050'");
        assertThatThrownBy(() -> productionChecks().run(null)).isInstanceOf(IllegalStateException.class);

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'PAT-00050'");
        assertThatCode(() -> productionChecks().run(null)).doesNotThrowAnyException();
    }
}
