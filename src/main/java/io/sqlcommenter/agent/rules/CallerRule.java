package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/** Tags the application class.method that triggered the SQL query. */
public class CallerRule implements CommentRule {

    private String appPackage;

    @Override public String getId() { return "caller"; }
    @Override public String getDescription() { return "Tags the application caller class/method"; }

    @Override
    public void configure(Properties props) { appPackage = props.getProperty("rule.caller.app_package"); }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        for (StackTraceElement frame : ctx.getStackTrace()) {
            String cls = frame.getClassName();
            if (appPackage != null && !cls.startsWith(appPackage)) continue;
            if (cls.contains("$Proxy") || cls.contains("$$") || cls.contains("CGLIB")) continue;
            String simpleCls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
            return Collections.singletonMap("caller", simpleCls + "." + frame.getMethodName());
        }
        return Collections.emptyMap();
    }
}
