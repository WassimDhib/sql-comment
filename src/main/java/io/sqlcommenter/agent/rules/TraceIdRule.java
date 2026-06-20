package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/**
 * Injects W3C traceparent / tracestate from MDC, OTel, or a custom supplier.
 */
public class TraceIdRule implements CommentRule {

    private static volatile java.util.function.Supplier<String> customSupplier = null;

    public static void setSupplier(java.util.function.Supplier<String> supplier) {
        customSupplier = supplier;
    }

    @Override public String getId() { return "traceparent"; }
    @Override public String getDescription() { return "Injects W3C traceparent/tracestate from MDC or OTel"; }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        Map<String, String> tags = new LinkedHashMap<>();

        if (customSupplier != null) {
            try {
                String tid = customSupplier.get();
                if (tid != null && !tid.isEmpty()) { tags.put("traceparent", tid); return tags; }
            } catch (Exception ignored) {}
        }

        String traceparent = getMdcValue("traceparent");
        if (traceparent != null) {
            tags.put("traceparent", traceparent);
            String tracestate = getMdcValue("tracestate");
            if (tracestate != null) tags.put("tracestate", tracestate);
            return tags;
        }

        String b3TraceId = getMdcValue("X-B3-TraceId");
        String b3SpanId  = getMdcValue("X-B3-SpanId");
        if (b3TraceId != null && b3SpanId != null) {
            tags.put("traceparent", "00-" + b3TraceId + "-" + b3SpanId + "-01");
            return tags;
        }

        String sysProp = System.getProperty("otel.trace.id");
        if (sysProp != null) tags.put("traceparent", sysProp);
        return tags;
    }

    private static String getMdcValue(String key) {
        try {
            Class<?> mdc = Class.forName("org.slf4j.MDC");
            Object val = mdc.getMethod("get", String.class).invoke(null, key);
            return val != null ? val.toString() : null;
        } catch (Exception e) { return null; }
    }
}
