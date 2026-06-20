package io.sqlcommenter.agent.rules;
import io.sqlcommenter.agent.context.QueryContext;
import java.util.Collections;
import java.util.Map;
public class ThreadRule implements CommentRule {
    public String getId() { return "thread"; }
    public String getDescription() { return "Tags the executing thread name"; }
    public boolean isFastPath() { return true; }
    public Map<String, String> extract(QueryContext ctx) {
        return Collections.singletonMap("thread", ctx.getThreadName());
    }
}
