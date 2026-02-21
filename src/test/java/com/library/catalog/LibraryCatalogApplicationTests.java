package com.library.catalog;

import com.library.catalog.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class LibraryCatalogApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // Verifies: Spring context starts, Testcontainers PostgreSQL spins up,
        // Flyway runs all migrations, Hibernate validates entity mappings.
    }
}
