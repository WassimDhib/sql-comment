package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/** Tags the deployment environment (prod, staging, dev…). */
public class EnvironmentRule implements CommentRule {

    private String overrideEnv;

    @Override public String getId() { return "environment"; }
    @Override public String getDescription() { return "Tags the deployment environment"; }

    @Override
    public void configure(Properties props) { overrideEnv = props.getProperty("rule.environment.name"); }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        String env = overrideEnv;
        if (env == null) env = System.getenv("SPRING_PROFILES_ACTIVE");
        if (env == null) env = System.getenv("ENV");
        if (env == null) env = System.getenv("ENVIRONMENT");
        if (env == null) env = System.getProperty("env");
        if (env == null) return Collections.emptyMap();
        return Collections.singletonMap("environment", env);
    }
}
