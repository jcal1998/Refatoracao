package com.saas.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BillingServiceFactoryTest {

    @Test
    void wiresTheServiceFromEnvironmentWithoutOpeningConnections() {
        Map<String, String> env = Map.of(
                "BILLING_DB_URL", "jdbc:postgresql://localhost:5432/saas_db",
                "BILLING_DB_USER", "billing",
                "BILLING_DB_PASSWORD", "secret");

        assertThat(BillingServiceFactory.create(env)).isNotNull();
    }

    @Test
    void credentialsAreNoLongerHardcoded() {
        assertThatThrownBy(() -> BillingServiceFactory.create(Map.of("BILLING_DB_URL", "jdbc:postgresql://db/saas")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BILLING_DB_USER");
    }
}
