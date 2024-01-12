package org.opensearch.migrations.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class RootOtelContext implements IRootOtelContext {
    private final OpenTelemetry openTelemetryImpl;
    private final String scopeName;
    @Getter
    @Setter
    Exception observedExceptionToIncludeInMetrics;

    public static OpenTelemetry initializeOpenTelemetryForCollector(@NonNull String collectorEndpoint,
                                                                    @NonNull String serviceName) {
        var serviceResource = Resource.getDefault().toBuilder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .build();

        final var spanProcessor = BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        .setTimeout(2, TimeUnit.SECONDS)
                        .build())
                .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                .build();
        final var metricReader = PeriodicMetricReader.builder(OtlpGrpcMetricExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        // see https://opentelemetry.io/docs/specs/otel/metrics/sdk_exporters/prometheus/
                        // "A Prometheus Exporter MUST only support Cumulative Temporality."
                        //.setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                        .build())
                .setInterval(Duration.ofMillis(1000))
                .build();
        final var logProcessor = BatchLogRecordProcessor.builder(OtlpGrpcLogRecordExporter.builder()
                        .setEndpoint(collectorEndpoint)
                        .build())
                .build();

        var openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().setResource(serviceResource)
                        .addSpanProcessor(spanProcessor).build())
                .setMeterProvider(SdkMeterProvider.builder().setResource(serviceResource)
                        .registerMetricReader(metricReader).build())
                .setLoggerProvider(SdkLoggerProvider.builder().setResource(serviceResource)
                        .addLogRecordProcessor(logProcessor).build())
                .build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        return openTelemetrySdk;
    }

    public static OpenTelemetry initializeOpenTelemetry(String collectorEndpoint, String serviceName) {
        return Optional.ofNullable(collectorEndpoint)
                .map(endpoint -> initializeOpenTelemetryForCollector(endpoint, serviceName))
                .orElse(OpenTelemetrySdk.builder().build());
    }


    public RootOtelContext(String scopeName) {
        this(scopeName, null);
    }

    public RootOtelContext(String scopeName, String collectorEndpoint, String serviceName) {
        this(scopeName, initializeOpenTelemetry(collectorEndpoint, serviceName));
    }

    public RootOtelContext(String scopeName, OpenTelemetry sdk) {
        openTelemetryImpl = sdk != null ? sdk : initializeOpenTelemetry(null, null);
        this.scopeName = scopeName;
    }

    @Override
    public RootOtelContext getEnclosingScope() {
        return null;
    }

    OpenTelemetry getOpenTelemetry() {
        return openTelemetryImpl;
    }

    @Override
    public MeterProvider getMeterProvider() {
        return getOpenTelemetry().getMeterProvider();
    }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder; // nothing more to do
    }

    private static SpanBuilder addLinkedToBuilder(Span linkedSpanContext, SpanBuilder spanBuilder) {
        return Optional.ofNullable(linkedSpanContext)
                .map(Span::getSpanContext).map(spanBuilder::addLink).orElse(spanBuilder);
    }

    private static Span buildSpanWithParent(SpanBuilder builder, Attributes attrs, Span parentSpan,
                                            Span linkedSpanContext) {
        return addLinkedToBuilder(linkedSpanContext, Optional.ofNullable(parentSpan)
                .map(p -> builder.setParent(Context.current().with(p)))
                .orElseGet(builder::setNoParent))
                .startSpan().setAllAttributes(attrs);
    }

    @Override
    public Span buildSpan(IInstrumentationAttributes enclosingScope,
                          String spanName, Span linkedSpan, AttributesBuilder attributesBuilder) {
        var parentSpan = enclosingScope.getCurrentSpan();
        var spanBuilder = getOpenTelemetry().getTracer(scopeName).spanBuilder(spanName);
        return buildSpanWithParent(spanBuilder, getPopulatedSpanAttributes(attributesBuilder), parentSpan, linkedSpan);
    }
}
