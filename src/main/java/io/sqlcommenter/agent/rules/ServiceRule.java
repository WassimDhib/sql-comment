package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/**
 * Extracts the top-level business service name from the call stack.
 *
 * <h2>Strategy</h2>
 * <p>Scans the thread stack bottom-to-top (from the SQL call outward to the entry point)
 * and returns the <strong>outermost</strong> frame within the configured application
 * package ({@code rule.service.app_package}) that is not a proxy and not excluded.
 * This frame represents the highest-level business entry point — the "service".
 *
 * <p>The raw thread stack is scanned directly (not {@link QueryContext}'s filtered window)
 * to handle deep stacks typical of layered architectures (Spring AOP + EJB + ORM
 * can easily reach 100-150 frames).
 *
 * <h2>Name extraction modes</h2>
 * <table>
 *   <tr><th>Mode</th><th>Input class</th><th>Output</th></tr>
 *   <tr><td>{@code subpackage} (default)</td>
 *       <td>{@code com.example.app.orders.impl.OrderServiceImpl}</td>
 *       <td>{@code orders}</td></tr>
 *   <tr><td>{@code classname}</td>
 *       <td>{@code com.example.app.orders.impl.OrderServiceImpl}</td>
 *       <td>{@code OrderServiceImpl}</td></tr>
 *   <tr><td>{@code class_method}</td>
 *       <td>{@code com.example.app.orders.impl.OrderServiceImpl.createOrder}</td>
 *       <td>{@code OrderServiceImpl.createOrder}</td></tr>
 * </table>
 *
 * <h2>Configuration ({@code sqlcommenter.properties})</h2>
 * <pre>
 * rules=...,service
 *
 * # Root package to scan for app frames (required)
 * rule.service.app_package=com.example.app
 *
 * # Known prefix to strip when extracting the subpackage name (optional)
 * # e.g. com.example.app.services.orders.impl.OrderService → "orders"
 * rule.service.prefix=com.example.app.services
 *
 * # Extraction mode: subpackage (default) | classname | class_method
 * rule.service.extract=subpackage
 *
 * # Packages to skip even if within app_package (comma-separated, optional)
 * # Useful to skip internal infrastructure layers (persistence, framework, etc.)
 * rule.service.exclude_packages=com.example.app.persistence,com.example.app.framework
 *
 * # Maximum stack depth to scan (default 200)
 * rule.service.max_depth=200
 * </pre>
 *
 * <h2>Example output</h2>
 * <pre>
 * SELECT * FROM orders WHERE id = ?
 *   &#47;*service='orders',caller='OrderServiceImpl.createOrder',framework='spring'*&#47;
 * </pre>
 */
public class ServiceRule implements CommentRule {

    private static final String DEFAULT_APP_PKG = "com.example.app";
    private static final String DEFAULT_PREFIX  = "";
    private static final String DEFAULT_EXTRACT = "subpackage";
    private static final int    DEFAULT_DEPTH   = 200;

    private String   appPackage      = DEFAULT_APP_PKG;
    private String   prefix          = DEFAULT_PREFIX;
    private String   extractMode     = DEFAULT_EXTRACT;
    private String[] excludePackages = new String[0];
    private int      maxDepth        = DEFAULT_DEPTH;

    // -------------------------------------------------------------------------
    // CommentRule
    // -------------------------------------------------------------------------

    @Override public String getId()          { return "service"; }
    @Override public String getDescription() { return "Top-level service caller from app stack"; }

    @Override
    public void configure(Properties props) {
        appPackage  = props.getProperty("rule.service.app_package", DEFAULT_APP_PKG);
        prefix      = props.getProperty("rule.service.prefix",      DEFAULT_PREFIX);
        extractMode = props.getProperty("rule.service.extract",     DEFAULT_EXTRACT);

        String excl = props.getProperty("rule.service.exclude_packages", "");
        if (!excl.trim().isEmpty()) {
            String[] parts = excl.split(",");
            excludePackages = new String[parts.length];
            for (int i = 0; i < parts.length; i++) excludePackages[i] = parts[i].trim();
        }

        try {
            maxDepth = Integer.parseInt(
                    props.getProperty("rule.service.max_depth", String.valueOf(DEFAULT_DEPTH)));
        } catch (NumberFormatException e) {
            maxDepth = DEFAULT_DEPTH;
        }
    }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        // Use the raw thread stack — QueryContext's filtered window may be too shallow
        // for deeply layered architectures (Spring AOP + EJB = 100+ frames).
        StackTraceElement[] raw = Thread.currentThread().getStackTrace();

        StackTraceElement topFrame = findTopLevelFrame(raw);
        if (topFrame == null) return Collections.emptyMap();

        String name = extractName(topFrame);
        if (name == null || name.isEmpty()) return Collections.emptyMap();

        return Collections.singletonMap("service", name);
    }

    // -------------------------------------------------------------------------
    // Stack walking
    // -------------------------------------------------------------------------

    /**
     * Scans the stack bottom-to-top and returns the outermost (last/deepest) app frame
     * that is not a proxy and not in an excluded package.
     */
    private StackTraceElement findTopLevelFrame(StackTraceElement[] stack) {
        int limit = Math.min(stack.length, maxDepth);

        // Bottom-to-top: outermost caller = highest-level service
        for (int i = limit - 1; i >= 0; i--) {
            StackTraceElement frame = stack[i];
            String cls = frame.getClassName();
            if (!cls.startsWith(appPackage)) continue;
            if (isProxy(cls))                continue;
            if (isExcluded(cls))             continue;
            return frame;
        }

        // Fallback: top-to-bottom, first non-excluded app frame
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = stack[i];
            String cls = frame.getClassName();
            if (!cls.startsWith(appPackage)) continue;
            if (isProxy(cls))                continue;
            if (isExcluded(cls))             continue;
            return frame;
        }

        return null;
    }

    private boolean isProxy(String cls) {
        return cls.contains("$Proxy")
            || cls.contains("$$")
            || cls.contains("CGLIB")
            || cls.contains("$EnhancerBy")
            || cls.contains("$FastClass")
            || cls.contains("_$logger");
    }

    private boolean isExcluded(String cls) {
        for (String ex : excludePackages) {
            if (!ex.isEmpty() && cls.startsWith(ex)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Name extraction
    // -------------------------------------------------------------------------

    private String extractName(StackTraceElement frame) {
        String cls    = frame.getClassName();
        String method = frame.getMethodName();
        String simple = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;

        if ("classname".equals(extractMode))    return simple;
        if ("class_method".equals(extractMode)) return simple + "." + method;
        return extractSubpackage(cls, simple);   // subpackage (default)
    }

    /**
     * Strips the known prefix and returns the first remaining package component.
     *
     * <pre>
     * prefix = "com.example.app.services"
     * class  = "com.example.app.services.orders.impl.OrderServiceImpl"
     * result = "orders"
     * </pre>
     */
    private String extractSubpackage(String cls, String simple) {
        String pfx = (prefix != null && !prefix.isEmpty()) ? prefix : appPackage;
        if (cls.startsWith(pfx + ".")) {
            String rest = cls.substring(pfx.length() + 1);
            int dot = rest.indexOf('.');
            return dot > 0 ? rest.substring(0, dot) : rest;
        }
        return simple;
    }
}
