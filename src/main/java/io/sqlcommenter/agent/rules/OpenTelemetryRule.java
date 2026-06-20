package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/** Injects OTel span context (traceparent, service_name) when OTel agent is present. */
public class OpenTelemetryRule implements CommentRule {

    @Override public String getId() { return "opentelemetry"; }
    @Override public String getDescription() { return "Injects OTel span context attributes"; }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        Map<String, String> tags = new LinkedHashMap<>();
        try {
            Class<?> otelClass = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            Object otel = otelClass.getMethod("get").invoke(null);
            Object tracer = otelClass.getMethod("getTracer", String.class).invoke(otel, "sqlcommenter");
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Object span = spanClass.getMethod("current").invoke(null);
            Object spanCtx = span.getClass().getMethod("getSpanContext").invoke(span);
            String traceId = (String) spanCtx.getClass().getMethod("getTraceId").invoke(spanCtx);
            String spanId  = (String) spanCtx.getClass().getMethod("getSpanId").invoke(spanCtx);
            if (traceId != null && !traceId.equals("00000000000000000000000000000000")) {
                tags.put("traceparent", "00-" + traceId + "-" + spanId + "-01");
            }
        } catch (Exception ignored) {}

        String serviceName = System.getProperty("otel.service.name");
        if (serviceName == null) serviceName = System.getenv("OTEL_SERVICE_NAME");
        if (serviceName != null && !serviceName.isEmpty()) tags.put("service_name", serviceName);
        return tags;
    }
}
