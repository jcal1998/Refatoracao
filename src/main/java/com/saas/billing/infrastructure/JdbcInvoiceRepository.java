package com.saas.billing.infrastructure;

import com.saas.billing.domain.Invoice;
import com.saas.billing.port.BillingInfrastructureException;
import com.saas.billing.port.InvoiceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class JdbcInvoiceRepository implements InvoiceRepository {

    // created_at continua vindo do NOW() do banco, como no legado.
    private static final String INSERT =
            "INSERT INTO invoices(customer_id, amount, status, created_at) VALUES (?, ?, ?, NOW())";

    private final JdbcConnectionFactory connections;

    public JdbcInvoiceRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public void save(Invoice invoice) {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, invoice.customerId());
            statement.setBigDecimal(2, invoice.amount().rounded().amount());
            statement.setString(3, invoice.status().name());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new BillingInfrastructureException("Falha ao gravar a fatura do cliente " + invoice.customerId(), e);
        }
    }
}
