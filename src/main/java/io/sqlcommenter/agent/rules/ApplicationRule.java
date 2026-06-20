package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/** Tags the application name and version from system properties or env vars. */
public class ApplicationRule implements CommentRule {

    private String overrideName;
    private String overrideVersion;

    @Override public String getId() { return "application"; }
    @Override public String getDescription() { return "Tags application name and version"; }

    @Override
    public void configure(Properties props) {
        overrideName    = props.getProperty("rule.application.name");
        overrideVersion = props.getProperty("rule.application.version");
    }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        Map<String, String> tags = new LinkedHashMap<>();
        String name = resolve(overrideName,
                System.getProperty("spring.application.name"),
                System.getenv("OTEL_SERVICE_NAME"), System.getenv("APP_NAME"));
        if (name != null) tags.put("application", name);
        String version = resolve(overrideVersion,
                System.getProperty("spring.application.version"), System.getenv("APP_VERSION"));
        if (version != null) tags.put("app_version", version);
        return tags;
    }

    @SafeVarargs
    private static <T> T resolve(T... candidates) {
        for (T c : candidates) { if (c != null && !c.toString().isEmpty()) return c; }
        return null;
    }
}
