package com.saas.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.InvoiceStatus;
import com.saas.billing.domain.Money;
import com.saas.billing.port.BillingInfrastructureException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Adaptadores JDBC contra um H2 em memória: valida o SQL sem servidor de banco. */
class JdbcRepositoriesTest {

    private final JdbcConnectionFactory database =
            new JdbcConnectionFactory("jdbc:h2:mem:billing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");

    @BeforeEach
    void createSchema() throws SQLException {
        try (Connection connection = database.open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS customers");
            statement.execute("DROP TABLE IF EXISTS invoices");
            statement.execute("CREATE TABLE customers (id VARCHAR(20) PRIMARY KEY, email VARCHAR(100), status VARCHAR(20))");
            statement.execute("CREATE TABLE invoices (customer_id VARCHAR(20), amount NUMERIC(12, 2), status VARCHAR(10), created_at TIMESTAMP)");
            statement.execute("INSERT INTO customers VALUES ('C-1', 'ana@cliente.com', 'ACTIVE')");
        }
    }

    @Test
    void findsCustomerById() {
        assertThat(new JdbcCustomerRepository(database).findById("C-1"))
                .contains(new Customer("C-1", "ana@cliente.com", "ACTIVE"));
    }

    @Test
    void unknownCustomerIsEmpty() {
        assertThat(new JdbcCustomerRepository(database).findById("C-404")).isEmpty();
    }

    @Test
    void savesInvoiceRoundedToCentsWithDatabaseTimestamp() throws SQLException {
        new JdbcInvoiceRepository(database).save(new Invoice("C-1", Money.of("91.225"), InvoiceStatus.PAID));

        try (Connection connection = database.open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT customer_id, amount, status, created_at FROM invoices")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("customer_id")).isEqualTo("C-1");
            assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("91.23");
            assertThat(rs.getString("status")).isEqualTo("PAID");
            assertThat(rs.getTimestamp("created_at")).isNotNull();
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void sqlFailuresBecomeBillingInfrastructureException() {
        JdbcConnectionFactory unreachable = new JdbcConnectionFactory("jdbc:unknown:billing", "sa", "");

        assertThatThrownBy(() -> new JdbcCustomerRepository(unreachable).findById("C-1"))
                .isInstanceOf(BillingInfrastructureException.class)
                .hasCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> new JdbcInvoiceRepository(unreachable).save(new Invoice("C-1", Money.ZERO, InvoiceStatus.FAILED)))
                .isInstanceOf(BillingInfrastructureException.class)
                .hasCauseInstanceOf(SQLException.class);
    }
}
