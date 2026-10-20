package com.saas.billing;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Driver JDBC de teste que intercepta a URL PostgreSQL fixa no código legado
 * e a redireciona para um H2 em memória. Só existe porque o legado não permite
 * injetar a conexão: é o "juro" pago para conseguir testá-lo sem infraestrutura.
 */
public final class PostgresToH2Driver implements Driver {

    static final String H2_URL = "jdbc:h2:mem:saas_db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private static final Driver H2 = new org.h2.Driver();

    static volatile boolean failConnections;

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        if (failConnections) {
            throw new SQLException("Connection to localhost:5432 refused");
        }
        Properties h2Info = new Properties();
        h2Info.setProperty("user", "sa");
        return H2.connect(H2_URL, h2Info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
