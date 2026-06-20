package io.sqlcommenter.agent.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * A {@link DataSource} decorator that wraps every {@link Connection} it returns
 * with {@link SqlCommenterConnectionProxy}.
 *
 * <p>Drop-in replacement for any DataSource — no bytecode manipulation required.
 *
 * <h2>Spring Boot usage</h2>
 * <pre>
 * {@literal @}Bean
 * public DataSource dataSource(DataSource delegate) {
 *     return new SqlCommenterDataSourceProxy(delegate);
 * }
 * </pre>
 *
 * <h2>Plain JDBC usage</h2>
 * <pre>
 * DataSource ds = new SqlCommenterDataSourceProxy(HikariPool.create(config));
 * try (Connection conn = ds.getConnection()) {
 *     // All SQL issued through conn will carry comments
 * }
 * </pre>
 */
public final class SqlCommenterDataSourceProxy implements DataSource {

    private final DataSource delegate;

    public SqlCommenterDataSourceProxy(DataSource delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate DataSource must not be null");
        this.delegate = delegate;
    }

    // -------------------------------------------------------------------------
    // Connection factory — the key interception point
    // -------------------------------------------------------------------------

    @Override
    public Connection getConnection() throws SQLException {
        return SqlCommenterConnectionProxy.wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return SqlCommenterConnectionProxy.wrap(delegate.getConnection(username, password));
    }

    // -------------------------------------------------------------------------
    // Delegation boilerplate
    // -------------------------------------------------------------------------

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) return iface.cast(this);
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass()) || delegate.isWrapperFor(iface);
    }

    /** Access to the underlying DataSource for testing or diagnostics. */
    public DataSource getDelegate() {
        return delegate;
    }
}
