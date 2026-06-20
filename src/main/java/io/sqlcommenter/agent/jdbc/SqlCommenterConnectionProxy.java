package io.sqlcommenter.agent.jdbc;

import io.sqlcommenter.agent.core.RuleEngine;
import io.sqlcommenter.agent.transformer.SqlCommenterRuntime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Alternative to bytecode transformation: wraps a {@link Connection} in a JDK
 * dynamic proxy that intercepts {@code prepareStatement}, {@code prepareCall},
 * {@code createStatement} and injects SQL comments.
 *
 * <h2>When to use this instead of the agent</h2>
 * <p>Some environments (OSGi containers, GraalVM native images, certain app servers)
 * make bytecode retransformation difficult or impossible.
 * In those cases, wrap your {@link DataSource} using {@link #wrap(Connection)}:
 *
 * <pre>
 * // In a Spring @Configuration:
 * {@literal @}Bean
 * public DataSource dataSource(DataSource original) {
 *     return SqlCommenterDataSourceProxy.wrap(original);
 * }
 * </pre>
 *
 * <p>Or wrap individual connections:
 * <pre>
 * Connection conn = SqlCommenterConnectionProxy.wrap(dataSource.getConnection());
 * </pre>
 *
 * <p>This approach does not require {@code -javaagent} on the command line.
 */
public final class SqlCommenterConnectionProxy implements InvocationHandler {

    private static final Logger LOG = Logger.getLogger(SqlCommenterConnectionProxy.class.getName());

    private static final Set<String> SQL_METHODS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "prepareStatement", "prepareCall", "nativeSQL"
    ));

    private final Connection delegate;

    private SqlCommenterConnectionProxy(Connection delegate) {
        this.delegate = delegate;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Wraps a {@link Connection} with SQL comment injection.
     *
     * @param connection the raw connection from a DataSource or DriverManager
     * @return a proxy that injects comments into all SQL passed to it
     */
    public static Connection wrap(Connection connection) {
        if (connection == null) return null;
        // Avoid double-wrapping
        if (Proxy.isProxyClass(connection.getClass())
                && Proxy.getInvocationHandler(connection) instanceof SqlCommenterConnectionProxy) {
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(
                connection.getClass().getClassLoader(),
                new Class[]{Connection.class},
                new SqlCommenterConnectionProxy(connection));
    }

    // -------------------------------------------------------------------------
    // InvocationHandler
    // -------------------------------------------------------------------------

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        // Intercept methods whose first argument is a SQL string
        if (SQL_METHODS.contains(name) && args != null && args.length > 0
                && args[0] instanceof String) {
            args[0] = instrumentSql((String) args[0]);
        }

        Object result = method.invoke(delegate, args);

        // Wrap returned Statement/PreparedStatement objects too
        if (result instanceof PreparedStatement) {
            return SqlCommenterStatementProxy.wrapPrepared((PreparedStatement) result);
        }
        if (result instanceof CallableStatement) {
            return SqlCommenterStatementProxy.wrapCallable((CallableStatement) result);
        }
        if (result instanceof Statement) {
            return SqlCommenterStatementProxy.wrapStatement((Statement) result);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String instrumentSql(String sql) {
        try {
            return SqlCommenterRuntime.instrument(sql);
        } catch (Exception e) {
            LOG.warning("[ConnectionProxy] Failed to instrument SQL: " + e.getMessage());
            return sql;
        }
    }
}

// =============================================================================
//  Statement proxy
// =============================================================================

/**
 * Proxy for {@link Statement}/{@link PreparedStatement}/{@link CallableStatement}
 * that intercepts {@code execute*} calls to inject SQL comments.
 */
final class SqlCommenterStatementProxy implements InvocationHandler {

    private static final Logger LOG = Logger.getLogger(SqlCommenterStatementProxy.class.getName());

    private static final Set<String> SQL_METHODS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
            "addBatch"
    ));

    private final Statement delegate;

    private SqlCommenterStatementProxy(Statement delegate) {
        this.delegate = delegate;
    }

    static Statement wrapStatement(Statement s) {
        return (Statement) Proxy.newProxyInstance(
                s.getClass().getClassLoader(),
                new Class[]{Statement.class},
                new SqlCommenterStatementProxy(s));
    }

    static PreparedStatement wrapPrepared(PreparedStatement ps) {
        return (PreparedStatement) Proxy.newProxyInstance(
                ps.getClass().getClassLoader(),
                new Class[]{PreparedStatement.class},
                new SqlCommenterStatementProxy(ps));
    }

    static CallableStatement wrapCallable(CallableStatement cs) {
        return (CallableStatement) Proxy.newProxyInstance(
                cs.getClass().getClassLoader(),
                new Class[]{CallableStatement.class},
                new SqlCommenterStatementProxy(cs));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (SQL_METHODS.contains(name) && args != null && args.length > 0
                && args[0] instanceof String) {
            args[0] = instrumentSql((String) args[0]);
        }
        return method.invoke(delegate, args);
    }

    private static String instrumentSql(String sql) {
        try {
            return SqlCommenterRuntime.instrument(sql);
        } catch (Exception e) {
            LOG.warning("[StatementProxy] Failed to instrument SQL: " + e.getMessage());
            return sql;
        }
    }
}
