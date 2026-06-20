package io.sqlcommenter.agent.context;

import java.util.*;

/**
 * Immutable snapshot of runtime metadata available when a SQL query is executed.
 *
 * <p>Created once per query execution by the JDBC interceptor and passed to every
 * active {@link io.sqlcommenter.agent.rules.CommentRule}.
 *
 * <p>Thread-safe: all fields are final or unmodifiable collections.
 */
public final class QueryContext {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** The original SQL string before any comment injection. */
    private final String originalSql;

    /** Current thread at the time of query execution. */
    private final Thread callerThread;

    /**
     * Captured stack frames above the JDBC call site.
     * Limited to {@link #MAX_STACK_DEPTH} frames for performance.
     */
    private final List<StackTraceElement> stackTrace;

    /**
     * Arbitrary context attributes set by framework integrations or user code
     * (e.g. MDC entries, request-scoped data propagated via ThreadLocal).
     */
    private final Map<String, Object> attributes;

    /** Epoch millis when this context was created (query start time). */
    private final long timestampMillis;

    /** Maximum stack frames captured to find the application call site. */
    public static final int MAX_STACK_DEPTH = 80;

    // -------------------------------------------------------------------------
    // Constructor (use Builder)
    // -------------------------------------------------------------------------

    private QueryContext(Builder b) {
        this.originalSql     = b.originalSql;
        this.callerThread    = b.callerThread;
        this.stackTrace      = Collections.unmodifiableList(new ArrayList<>(b.stackTrace));
        this.attributes      = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
        this.timestampMillis = b.timestampMillis;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Creates a QueryContext for the current thread and given SQL. */
    public static QueryContext forCurrentThread(String sql) {
        Thread t = Thread.currentThread();
        StackTraceElement[] rawStack = t.getStackTrace();
        List<StackTraceElement> appFrames = new ArrayList<>();

        boolean pastJdbc = false;
        int count = 0;
        for (StackTraceElement frame : rawStack) {
            String cls = frame.getClassName();
            // Skip JVM internals and agent classes
            if (!pastJdbc) {
                if (cls.startsWith("io.sqlcommenter.") || cls.startsWith("java.sql.")
                        || cls.startsWith("javax.sql.") || cls.startsWith("sun.reflect.")
                        || cls.startsWith("java.lang.reflect.")) {
                    pastJdbc = true;
                }
                continue;
            }
            // Skip remaining agent + JDK frames
            if (cls.startsWith("io.sqlcommenter.") || cls.startsWith("java.") 
                    || cls.startsWith("javax.") || cls.startsWith("sun.")) {
                continue;
            }
            appFrames.add(frame);
            if (++count >= MAX_STACK_DEPTH) break;
        }

        return new Builder(sql)
                .thread(t)
                .stackTrace(appFrames)
                .timestampMillis(System.currentTimeMillis())
                .build();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getOriginalSql() {
        return originalSql;
    }

    public Thread getCallerThread() {
        return callerThread;
    }

    public String getThreadName() {
        return callerThread != null ? callerThread.getName() : "unknown";
    }

    /** Returns the topmost application stack frame (most immediate caller). */
    public Optional<StackTraceElement> getCallerFrame() {
        return stackTrace.isEmpty() ? Optional.empty() : Optional.of(stackTrace.get(0));
    }

    public List<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    /**
     * Scans the stack for the first frame whose class name contains {@code pattern}.
     */
    public Optional<StackTraceElement> findFrame(String pattern) {
        for (StackTraceElement f : stackTrace) {
            if (f.getClassName().contains(pattern)) return Optional.of(f);
        }
        return Optional.empty();
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getAttribute(String key) {
        return Optional.ofNullable((T) attributes.get(key));
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    @Override
    public String toString() {
        return "QueryContext{sql='" + originalSql + "', thread='" + getThreadName() + "'}";
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {
        private final String originalSql;
        private Thread callerThread = Thread.currentThread();
        private List<StackTraceElement> stackTrace = Collections.emptyList();
        private Map<String, Object> attributes = new LinkedHashMap<>();
        private long timestampMillis = System.currentTimeMillis();

        public Builder(String originalSql) {
            this.originalSql = Objects.requireNonNull(originalSql, "sql must not be null");
        }

        public Builder thread(Thread t) { this.callerThread = t; return this; }
        public Builder stackTrace(List<StackTraceElement> s) { this.stackTrace = s; return this; }
        public Builder attribute(String key, Object value) { this.attributes.put(key, value); return this; }
        public Builder attributes(Map<String, Object> map) { this.attributes.putAll(map); return this; }
        public Builder timestampMillis(long ts) { this.timestampMillis = ts; return this; }
        public QueryContext build() { return new QueryContext(this); }
    }
}
