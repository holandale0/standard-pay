package org.leonardo.standardpay.webhooks.control;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * As entregas de webhook em log e em métrica.
 *
 * Chamado pelo {@link WebhookDispatcher} depois que o resultado de cada tentativa
 * já foi gravado — a transação de registro é própria e já confirmou quando isto
 * roda.
 */
@ApplicationScoped
public class WebhookTelemetry {

    static final String METRIC_DELIVERY = "standardpay.webhooks.delivery";
    static final String METRIC_PENDING = "standardpay.webhooks.pending";

    /** O que aconteceu com uma tentativa. */
    public enum Outcome {
        DELIVERED, RETRY, GAVE_UP;

        String tag() {
            return name().toLowerCase();
        }
    }

    @Inject
    MeterRegistry registry;

    private final AtomicLong pending = new AtomicLong();

    /**
     * Na subida, e não na primeira entrega: um gauge que só aparece depois do
     * primeiro webhook deixaria o painel vazio justamente quando nada funciona.
     */
    void register(@Observes StartupEvent startup) {
        Gauge.builder(METRIC_PENDING, pending, AtomicLong::get)
                .description("Webhook deliveries waiting to be sent, including those waiting for a retry")
                .register(registry);
    }

    void backlog(long waiting) {
        pending.set(waiting);
    }

    void attempt(String deliveryId, String eventType, int attempt, Outcome outcome, Duration took,
                 String error, Instant nextAttemptAt) {
        Timer.builder(METRIC_DELIVERY)
                .description("Time to deliver a webhook to the merchant, by outcome")
                .tag("event", eventType)
                .tag("outcome", outcome.tag())
                .register(registry)
                .record(took);

        switch (outcome) {
            case DELIVERED -> Log.infof("Webhook %s (%s) delivered on attempt %d in %d ms",
                    deliveryId, eventType, attempt, took.toMillis());
            case RETRY -> Log.warnf("Webhook %s (%s) attempt %d failed: %s; next attempt at %s",
                    deliveryId, eventType, attempt, error, nextAttemptAt);
            // ERROR: aqui alguém precisa olhar — o lojista não vai receber este evento sozinho.
            case GAVE_UP -> Log.errorf("Webhook %s (%s) gave up after %d attempts: %s",
                    deliveryId, eventType, attempt, error);
        }
    }
}
