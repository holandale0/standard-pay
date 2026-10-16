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
 *
 * Um estorno conta como feito quando conclui, não quando é pedido: o pendente
 * que depois falha nunca entra em {@value #METRIC_REFUNDS}.
 */
@ApplicationScoped
public class RefundTelemetry {

    static final String METRIC_REFUNDS = "standardpay.refunds";
    static final String METRIC_REFUNDED_AMOUNT = "standardpay.refunds.amount";
    static final String METRIC_PENDING = "standardpay.refunds.pending";
    static final String METRIC_FAILED = "standardpay.refunds.failed";

    /**
     * @param full   se não sobrou nada a estornar no pagamento
     * @param detail o código da falha, em FAILED
     */
    public record RefundEvent(Kind kind, String refundId, String paymentId, long amount, String currency,
                              boolean full, String detail) {
        public enum Kind { PENDING, SUCCEEDED, FAILED }
    }

    @Inject
    MeterRegistry registry;

    void on(@Observes(during = TransactionPhase.AFTER_SUCCESS) RefundEvent e) {
        switch (e.kind()) {
            case PENDING -> {
                registry.counter(METRIC_PENDING).increment();
                Log.infof("Refund %s on payment %s pending: %d %s (cents)",
                        e.refundId(), e.paymentId(), e.amount(), e.currency());
            }
            case SUCCEEDED -> {
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
            case FAILED -> {
                registry.counter(METRIC_FAILED, "reason", e.detail()).increment();
                Log.warnf("Refund %s on payment %s failed: %s; %d %s (cents) refundable again",
                        e.refundId(), e.paymentId(), e.detail(), e.amount(), e.currency());
            }
        }
    }
}
