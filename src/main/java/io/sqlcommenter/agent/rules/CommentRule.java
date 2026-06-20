package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;

import java.util.Map;

/**
 * A single rule that contributes key-value pairs to a SQL comment.
 *
 * <p>Rules are stateless and thread-safe. They receive a {@link QueryContext}
 * containing runtime metadata (caller stack, thread locals, system properties…)
 * and return the key-value pairs they want to embed in the SQL comment.
 *
 * <p>Example output:
 * <pre>
 *   SELECT * FROM users
 *   /* traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
 *      framework='spring',db_driver='postgresql-42.6.0' *&#47;
 * </pre>
 *
 * <h2>Implementing a custom rule</h2>
 * <pre>
 * public class MyTenantRule implements CommentRule {
 *     {@literal @}Override
 *     public String getId() { return "tenant"; }
 *
 *     {@literal @}Override
 *     public Map<String,String> extract(QueryContext ctx) {
 *         String tenantId = TenantContext.current(); // your own ThreadLocal
 *         if (tenantId == null) return Map.of();
 *         return Map.of("tenant_id", tenantId);
 *     }
 * }
 * </pre>
 *
 * <p>Then register it in {@code sqlcommenter.properties}:
 * <pre>
 *   rules=traceId,framework,tenant
 * </pre>
 * and add a line:
 * <pre>
 *   rule.tenant.class=com.example.MyTenantRule
 * </pre>
 */
public interface CommentRule {

    /**
     * Unique identifier for this rule (used in config {@code rules=...}).
     * Must be lowercase, alphanumeric + underscores only.
     */
    String getId();

    /**
     * Human-readable description shown in agent startup logs.
     */
    default String getDescription() {
        return "No description";
    }

    /**
     * Extracts zero or more key-value pairs to embed in the SQL comment.
     *
     * <p>Return an empty map (never {@code null}) when this rule has nothing
     * to contribute for the current request.
     *
     * <p>Keys and values must not be null.
     *
     * @param ctx runtime context for the current query execution
     * @return map of comment key-value pairs, insertion order is preserved
     */
    Map<String, String> extract(QueryContext ctx);

    /**
     * Called once during agent bootstrap to allow the rule to read its
     * own config keys from {@code sqlcommenter.properties}.
     *
     * <p>Default implementation does nothing.
     */
    default void configure(java.util.Properties props) {
        // optional hook
    }

    /**
     * Whether this rule is safe to call in a tight inner loop.
     * Rules that do I/O, lock acquisition, or expensive computation should
     * return {@code false} so the engine can optionally skip them under load.
     *
     * <p>Default: {@code true}.
     */
    default boolean isFastPath() {
        return true;
    }
}
