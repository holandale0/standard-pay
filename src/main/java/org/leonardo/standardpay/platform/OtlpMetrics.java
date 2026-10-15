package org.leonardo.standardpay.platform;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Empurra as métricas do Micrometer por OTLP, para o mesmo coletor que recebe os
 * traces e os logs.
 *
 * O Quarkus soma este registro ao do Prometheus: as métricas continuam legíveis
 * em /q/metrics e nos testes, e também saem por OTLP. A ponte pronta
 * (quarkus-micrometer-opentelemetry) fazia só a segunda metade — os medidores
 * dela não devolvem valor nenhum.
 *
 * Liga e desliga junto com o resto da telemetria: com quarkus.otel.sdk.disabled,
 * o registro existe mas não publica, e não fica tentando falar com um coletor
 * que não está no ar.
 */
public class OtlpMetrics {

    @Produces
    @Singleton
    OtlpMeterRegistry otlpMeterRegistry(
            @ConfigProperty(name = "quarkus.otel.sdk.disabled") boolean telemetryDisabled,
            @ConfigProperty(name = "standardpay.otel.endpoint") String endpoint,
            @ConfigProperty(name = "standardpay.otel.metrics-interval") String interval,
            @ConfigProperty(name = "quarkus.otel.service.name") String serviceName) {

        OtlpConfig config = key -> switch (key) {
            case "otlp.enabled" -> String.valueOf(!telemetryDisabled);
            case "otlp.url" -> endpoint + "/v1/metrics";
            case "otlp.step" -> interval;
            case "otlp.resourceAttributes" -> "service.name=" + serviceName;
            default -> null;
        };
        return new OtlpMeterRegistry(config, Clock.SYSTEM);
    }
}
