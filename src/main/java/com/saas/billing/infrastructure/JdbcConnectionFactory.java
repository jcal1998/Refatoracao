package com.saas.billing.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/** Dados de conexão vindos da configuração, não mais fixos (e com senha) no código. */
public record JdbcConnectionFactory(String url, String user, String password) {

    public static JdbcConnectionFactory fromEnvironment(Map<String, String> env) {
        return new JdbcConnectionFactory(
                required(env, "BILLING_DB_URL"),
                required(env, "BILLING_DB_USER"),
                required(env, "BILLING_DB_PASSWORD"));
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória ausente: " + name);
        }
        return value;
    }
}
