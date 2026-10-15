package org.leonardo.standardpay.payments.control;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.leonardo.standardpay.payments.entity.Payment;

/**
 * O que acontece com as cobranças, em log e em métrica.
 *
 * O {@link PaymentProcessor} só dispara {@link PaymentEvent}; quem registra é
 * este observador, e só depois que a transação confirma ({@code AFTER_SUCCESS}).
 * Registrar no meio dela faria o log e a métrica afirmarem um pagamento aprovado
 * mesmo quando o commit falha depois — num sistema de pagamentos, a pior mentira
 * possível.
 *
 * As métricas não levam o id do lojista como rótulo, de propósito: cada valor
 * distinto de rótulo vira uma série no banco de métricas, e com um rótulo por
 * lojista o custo cresceria com a base de clientes. Quem quer saber de um lojista
 * procura nos logs, que trazem o merchantId em toda linha.
 */
@ApplicationScoped
public class PaymentTelemetry {

    static final String METRIC_CREATED = "standardpay.payments.created";
    static final String METRIC_SUCCEEDED = "standardpay.payments.succeeded";
    static final String METRIC_SUCCEEDED_AMOUNT = "standardpay.payments.succeeded.amount";
    static final String METRIC_DECLINED = "standardpay.payments.declined";
    static final String METRIC_CANCELED = "standardpay.payments.canceled";
    static final String METRIC_EXPIRED = "standardpay.payments.expired";
    private static final String CURRENCY = "currency";

    /** Uma mudança na cobrança, já com os dados copiados — a entidade some com a sessão. */
    public record PaymentEvent(Kind kind, String paymentId, long amount, String currency,
                               String method, String detail) {

        public enum Kind { CREATED, SUCCEEDED, DECLINED, CANCELED, EXPIRED }

        static PaymentEvent of(Kind kind, Payment p, String detail) {
            return new PaymentEvent(kind, p.id, p.amount, p.currency,
                    p.method == null ? null : p.method.name().toLowerCase(), detail);
        }
    }

    @Inject
    MeterRegistry registry;

    void on(@Observes(during = TransactionPhase.AFTER_SUCCESS) PaymentEvent e) {
        switch (e.kind()) {
            case CREATED -> {
                registry.counter(METRIC_CREATED, CURRENCY, e.currency()).increment();
                Log.infof("Payment %s created: %d %s (cents)", e.paymentId(), e.amount(), e.currency());
            }
            case SUCCEEDED -> {
                registry.counter(METRIC_SUCCEEDED, "method", e.method(), CURRENCY, e.currency()).increment();
                Counter.builder(METRIC_SUCCEEDED_AMOUNT)
                        .description("Total paid, in cents")
                        .baseUnit("cents")
                        .tag(CURRENCY, e.currency())
                        .register(registry)
                        .increment(e.amount());
                Log.infof("Payment %s succeeded via %s: %d %s (cents)",
                        e.paymentId(), e.method(), e.amount(), e.currency());
            }
            // Recusa é desfecho esperado de negócio, não falha do sistema: INFO, não WARN.
            case DECLINED -> {
                registry.counter(METRIC_DECLINED, "reason", e.detail()).increment();
                Log.infof("Payment %s declined: %s", e.paymentId(), e.detail());
            }
            case CANCELED -> {
                registry.counter(METRIC_CANCELED, "by", e.detail()).increment();
                Log.infof("Payment %s canceled by the %s", e.paymentId(), e.detail());
            }
            case EXPIRED -> {
                registry.counter(METRIC_EXPIRED).increment();
                Log.infof("Payment %s expired unpaid", e.paymentId());
            }
        }
    }
}
