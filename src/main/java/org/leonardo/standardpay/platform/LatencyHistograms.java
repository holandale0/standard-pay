package org.leonardo.standardpay.platform;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

/**
 * Faixas de latência das requisições HTTP e das entregas de webhook, para que o
 * coletor calcule percentis (p95, p99) somando instâncias.
 *
 * Sem isto, o Micrometer publica só contagem, soma e máximo — o histograma sai
 * com uma única faixa, +Inf, e qualquer percentil dá NaN. As faixas são fixas, e
 * não as ~70 automáticas: poucas séries por rota, e limites legíveis no painel.
 * Vão até 10s, o tempo máximo que um webhook espera pelo lojista.
 */
public class LatencyHistograms {

    private static final Set<String> TIMED = Set.of("http.server.requests", "standardpay.webhooks.delivery");

    private static final long[] BUCKETS_MS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};

    @Produces
    @Singleton
    MeterFilter latencyHistograms() {
        double[] nanos = Arrays.stream(BUCKETS_MS).mapToDouble(ms -> Duration.ofMillis(ms).toNanos()).toArray();
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!TIMED.contains(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(nanos)
                        .build()
                        .merge(config);
            }
        };
    }
}
