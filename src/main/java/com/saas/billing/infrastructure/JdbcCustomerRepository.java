package com.saas.billing.infrastructure;

import com.saas.billing.domain.Customer;
import com.saas.billing.port.BillingInfrastructureException;
import com.saas.billing.port.CustomerRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class JdbcCustomerRepository implements CustomerRepository {

    private static final String FIND_BY_ID = "SELECT email, status FROM customers WHERE id = ?";

    private final JdbcConnectionFactory connections;

    public JdbcCustomerRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public Optional<Customer> findById(String customerId) {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
            statement.setString(1, customerId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(customerId, rs.getString("email"), rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new BillingInfrastructureException("Falha ao consultar o cliente " + customerId, e);
        }
    }
}
