package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/** Tags the JDBC driver name based on the driver package in the call stack. */
public class DbDriverRule implements CommentRule {

    private static final Map<String, String> DRIVER_NAMES = new LinkedHashMap<>();
    static {
        DRIVER_NAMES.put("org.postgresql.",          "postgresql");
        DRIVER_NAMES.put("com.mysql.",               "mysql");
        DRIVER_NAMES.put("oracle.jdbc.",             "oracle");
        DRIVER_NAMES.put("com.microsoft.sqlserver.", "sqlserver");
        DRIVER_NAMES.put("org.h2.",                  "h2");
        DRIVER_NAMES.put("org.sqlite.",              "sqlite");
        DRIVER_NAMES.put("org.mariadb.",             "mariadb");
    }

    @Override public String getId() { return "db_driver"; }
    @Override public String getDescription() { return "Tags the JDBC driver name and version"; }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        for (StackTraceElement f : ctx.getStackTrace()) {
            for (Map.Entry<String, String> e : DRIVER_NAMES.entrySet()) {
                if (f.getClassName().startsWith(e.getKey())) return Collections.singletonMap("db_driver", e.getValue());
            }
        }
        return Collections.emptyMap();
    }
}
