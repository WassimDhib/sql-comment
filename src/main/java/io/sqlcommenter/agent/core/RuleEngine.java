package io.sqlcommenter.agent.core;

import io.sqlcommenter.agent.context.QueryContext;
import io.sqlcommenter.agent.rules.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates all active {@link CommentRule}s and assembles the SQL comment.
 */
public final class RuleEngine {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    private final List<CommentRule> rules;
    private final AgentConfig config;
    private final Map<String, String> staticTags;

    /** Diagnostic counters. */
    private volatile long callCount = 0;
    private volatile long emptyTagCount = 0;  // rules produced no tags

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static RuleEngine from(AgentConfig config) {
        List<CommentRule> allRules = new ArrayList<CommentRule>();

        String[] builtIns = {
            "io.sqlcommenter.agent.rules.TraceIdRule",
            "io.sqlcommenter.agent.rules.OpenTelemetryRule",
            "io.sqlcommenter.agent.rules.FrameworkRule",
            "io.sqlcommenter.agent.rules.DbDriverRule",
            "io.sqlcommenter.agent.rules.CallerRule",
            "io.sqlcommenter.agent.rules.ThreadRule",
            "io.sqlcommenter.agent.rules.ApplicationRule",
            "io.sqlcommenter.agent.rules.EnvironmentRule",
            "io.sqlcommenter.agent.rules.ServiceRule",
        };
        for (String cls : builtIns) {
            try {
                allRules.add((CommentRule) Class.forName(cls).getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                LOG.warning("[RuleEngine] Could not instantiate built-in rule " + cls + ": " + e.getMessage());
            }
        }

        loadCustomRules(allRules, config);

        Set<String> enabled = config.getEnabledRuleIds();
        List<CommentRule> active = new ArrayList<CommentRule>();
        for (CommentRule rule : allRules) {
            if (enabled.isEmpty() || enabled.contains(rule.getId())) {
                rule.configure(config.getRawProperties());
                active.add(rule);
            }
        }

        LOG.info("[RuleEngine] Active rules: " + getRuleIdList(active)
                + " | format=" + config.getFormat()
                + " | staticTags=" + config.getStaticTags()
                + " | jdbcPrefixes=" + config.getJdbcPrefixes().size() + " prefixes");

        return new RuleEngine(active, config);
    }

    private RuleEngine(List<CommentRule> rules, AgentConfig config) {
        this.rules      = Collections.unmodifiableList(rules);
        this.config     = config;
        this.staticTags = config.getStaticTags();
    }

    // -------------------------------------------------------------------------
    // Core instrumentation
    // -------------------------------------------------------------------------

    public String instrument(String sql) {
        if (sql == null || sql.trim().isEmpty()) return sql;

        callCount++;
        QueryContext ctx = QueryContext.forCurrentThread(sql);
        Map<String, String> tags = buildTags(ctx);

        if (tags.isEmpty()) {
            emptyTagCount++;
            // Log once when this happens (rules may not be producing output)
            if (emptyTagCount == 1) {
                LOG.warning("[RuleEngine] instrument() produced NO tags for first call."
                    + " SQL: " + preview(sql)
                    + " | Thread: " + ctx.getThreadName()
                    + " | Stack top: " + ctx.getCallerFrame().map(Object::toString).orElse("none")
                    + " | Rules: " + getRuleIdList(rules)
                    + " | StaticTags: " + staticTags);
            } else if (emptyTagCount % 500 == 0) {
                LOG.warning("[RuleEngine] Still producing NO tags after " + emptyTagCount
                    + " calls. Check rule configuration.");
            }
            return sql;
        }

        String comment = buildComment(tags);
        String result  = appendComment(sql, comment);

        if (config.isVerbose()) {
            LOG.fine("[RuleEngine] SQL instrumented: " + preview(result));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Tag building
    // -------------------------------------------------------------------------

    private Map<String, String> buildTags(QueryContext ctx) {
        Map<String, String> tags = new TreeMap<String, String>(staticTags);

        for (CommentRule rule : rules) {
            try {
                Map<String, String> pairs = rule.extract(ctx);
                if (pairs != null) {
                    for (Map.Entry<String, String> e : pairs.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                            tags.put(e.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warning("[RuleEngine] Rule '" + rule.getId() + "' threw: " + ex.getMessage());
            }
        }

        return tags;
    }

    // -------------------------------------------------------------------------
    // Comment formatting
    // -------------------------------------------------------------------------

    public String buildComment(Map<String, String> tags) {
        if (tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("/*");
        boolean first = true;
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String key = config.isUrlEncoded() ? encode(e.getKey())   : e.getKey();
            String val = config.isUrlEncoded() ? encode(e.getValue()) : e.getValue();
            sb.append(key).append("='").append(val).append("'");
        }
        sb.append("*/");
        return sb.toString();
    }

    public String appendComment(String sql, String comment) {
        if (comment == null || comment.isEmpty()) return sql;
        String trimmed = sql.stripTrailing();
        boolean hasSemicolon = trimmed.endsWith(";");
        if (hasSemicolon) trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        String result = trimmed + " " + comment;
        return hasSemicolon ? result + ";" : result;
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) { return value; }
    }

    public List<String> getRuleIds() {
        return getRuleIdList(rules);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> getRuleIdList(List<CommentRule> ruleList) {
        List<String> ids = new ArrayList<String>();
        for (CommentRule r : ruleList) ids.add(r.getId());
        return Collections.unmodifiableList(ids);
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    private static void loadCustomRules(List<CommentRule> target, AgentConfig config) {
        Properties props = config.getRawProperties();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("rule.") && key.endsWith(".class")) {
                String className = props.getProperty(key);
                try {
                    Class<?> clazz = Class.forName(className);
                    CommentRule rule = (CommentRule) clazz.getDeclaredConstructor().newInstance();
                    target.add(rule);
                    LOG.info("[RuleEngine] Loaded custom rule: " + rule.getId() + " → " + className);
                } catch (Exception e) {
                    LOG.warning("[RuleEngine] Failed to load custom rule '" + className + "': " + e.getMessage());
                }
            }
        }
    }
}
