package io.sqlcommenter.agent.transformer;

import java.util.Collections;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Static bridge called from transformed JDBC bytecode.
 * Must be visible from all JBoss module classloaders (via global-module).
 */
public final class SqlCommenterRuntime {

    private static final Logger LOG = Logger.getLogger(SqlCommenterRuntime.class.getName());

    public interface Instrumenter {
        String instrument(String sql);
    }

    private static volatile Instrumenter instrumenter;

    /** Classes that already triggered a NOT INSTRUMENTED warning — warn once only. */
    private static final Set<String> warnedClasses =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * Counter for total calls — logged periodically when verbose mode is on.
     * Using AtomicLong would be cleaner but adds a dependency; plain volatile + increment
     * is fine for diagnostic counters (occasional missed count is acceptable).
     */
    private static volatile long callCount = 0;
    private static volatile long instrumentedCount = 0;
    private static volatile long noopCount = 0;   // instrumenter null
    private static volatile long errorCount = 0;

    /** Log a diagnostic summary every N calls when verbose is enabled. */
    private static final long LOG_EVERY = 100;

    private SqlCommenterRuntime() {}

    // -------------------------------------------------------------------------
    // Called from transformed JDBC bytecode (hot path)
    // -------------------------------------------------------------------------

    public static String instrument(String sql) {
        callCount++;

        if (sql == null) return null;

        Instrumenter fn = instrumenter;
        if (fn == null) {
            noopCount++;
            // Log once — instrumenter was never set (agent not fully initialized)
            if (noopCount == 1) {
                LOG.warning("[SqlCommenterRuntime] instrument() called but Instrumenter is null."
                    + " Agent may not have initialized correctly."
                    + " Loader: " + getLoaderInfo());
            }
            return sql;
        }

        try {
            String result = fn.instrument(sql);
            instrumentedCount++;

            // Periodic diagnostic log (INFO level, visible without verbose)
            long c = callCount;
            if (c == 1) {
                LOG.info("[SqlCommenterRuntime] First SQL instrumented OK."
                    + " Loader: " + getLoaderInfo()
                    + " | fn loader: " + fn.getClass().getClassLoader()
                    + " | SQL preview: " + preview(result));
            } else if (c % LOG_EVERY == 0) {
                LOG.info("[SqlCommenterRuntime] Stats: calls=" + callCount
                    + " instrumented=" + instrumentedCount
                    + " noop=" + noopCount
                    + " errors=" + errorCount);
            }

            return result;
        } catch (Throwable t) {
            errorCount++;
            LOG.warning("[SqlCommenterRuntime] instrument() error: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return sql;
        }
    }

    /**
     * Called from the defensive catch block in transformed bytecode.
     * Logs a WARNING once per class when SqlCommenterRuntime is not visible
     * from that class's JBoss module.
     */
    public static void __warnOnce(Throwable error, String caller) {
        try {
            if (warnedClasses.size() > 50) return;
            if (warnedClasses.add(caller)) {
                LOG.warning("[SqlCommenterRuntime] NOT INSTRUMENTED: " + caller
                    + "\n  Cause: SqlCommenterRuntime not visible from this JBoss module"
                    + "\n  Error: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                    + "\n  Fix  : Install the JBoss module and declare it as global-module"
                    + "\n  Steps:"
                    + "\n    mkdir -p $JBOSS_HOME/modules/io/sqlcommenter/agent/main/"
                    + "\n    cp sqlcommenter-runtime.jar module.xml $JBOSS_HOME/modules/io/sqlcommenter/agent/main/"
                    + "\n    jboss-cli.sh --connect"
                    + "\n    /subsystem=ee:list-add(name=global-modules,value={name=io.sqlcommenter.agent})"
                    + "\n    :reload"
                    + "\n  See: JBOSS_INSTALL.md");
            }
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Agent lifecycle
    // -------------------------------------------------------------------------

    public static void setInstrumenter(Instrumenter fn) {
        instrumenter = fn;
        LOG.info("[SqlCommenterRuntime] Instrumenter registered."
                + " Runtime loader: " + getLoaderInfo()
                + " | Instrumenter loader: " + (fn != null ? fn.getClass().getClassLoader() : "null"));
    }

    public static void reset() {
        instrumenter = null;
        warnedClasses.clear();
        callCount = 0; instrumentedCount = 0; noopCount = 0; errorCount = 0;
    }

    public static boolean isActive() { return instrumenter != null; }

    public static String getLoaderInfo() {
        ClassLoader cl = SqlCommenterRuntime.class.getClassLoader();
        return cl == null ? "bootstrap" : cl.getClass().getSimpleName();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String preview(String s) {
        if (s == null) return "null";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
