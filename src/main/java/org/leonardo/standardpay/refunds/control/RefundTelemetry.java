package org.leonardo.standardpay.refunds.control;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/**
 * Estornos em log e em métrica — registrados só depois que a transação confirma,
 * pelo mesmo motivo de PaymentTelemetry.
 */
@ApplicationScoped
public class RefundTelemetry {

    static final String METRIC_REFUNDS = "standardpay.refunds";
    static final String METRIC_REFUNDED_AMOUNT = "standardpay.refunds.amount";

    public record RefundEvent(String refundId, String paymentId, long amount, String currency, boolean full) {
    }

    @Inject
    MeterRegistry registry;

    void on(@Observes(during = TransactionPhase.AFTER_SUCCESS) RefundEvent e) {
        registry.counter(METRIC_REFUNDS, "type", e.full() ? "full" : "partial").increment();
        Counter.builder(METRIC_REFUNDED_AMOUNT)
                .description("Total refunded, in cents")
                .baseUnit("cents")
                .tag("currency", e.currency())
                .register(registry)
                .increment(e.amount());
        Log.infof("Refund %s on payment %s: %d %s (cents), %s",
                e.refundId(), e.paymentId(), e.amount(), e.currency(),
                e.full() ? "nothing left to refund" : "partial");
    }
}
