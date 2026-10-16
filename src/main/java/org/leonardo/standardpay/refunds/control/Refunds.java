package org.leonardo.standardpay.refunds.control;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.leonardo.standardpay.payments.control.PaymentProcessor;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;
import org.leonardo.standardpay.payments.entity.PaymentView;
import org.leonardo.standardpay.payments.entity.RefundBehavior;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Ids;
import org.leonardo.standardpay.refunds.control.RefundTelemetry.RefundEvent;
import org.leonardo.standardpay.refunds.control.RefundTelemetry.RefundEvent.Kind;
import org.leonardo.standardpay.refunds.entity.Refund;
import org.leonardo.standardpay.webhooks.control.WebhookPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class Refunds {

    /** O código de falha dos estornos do cartão 4000 0000 0000 5126 — o mesmo do Stripe. */
    static final String FAILURE_CODE = "expired_or_canceled_card";

    /** O estorno e o pagamento depois dele — o webhook leva os dois. */
    public record RefundView(String id, String paymentId, long amount, String reason, String status,
                             String failureCode, String failureMessage, Instant createdAt, Instant settledAt,
                             PaymentView payment) {
    }

    @Inject
    PaymentProcessor payments;

    @Inject
    WebhookPublisher webhooks;

    @Inject
    Event<RefundEvent> events;

    @ConfigProperty(name = "standardpay.refunds.delayed-by")
    Duration delayedBy;

    @ConfigProperty(name = "standardpay.refunds.fails-after")
    Duration failsAfter;

    /**
     * Estorna {@code amount} centavos; sem valor, estorna tudo o que resta.
     *
     * Trava o pagamento antes de somar: dois estornos simultâneos de R$ 100
     * num pagamento de R$ 150 não podem passar os dois. O estorno pendente
     * já entra na soma pelo mesmo motivo.
     */
    @Transactional
    public RefundView refund(String merchantId, String paymentId, Long amount, String reason) {
        Payment p = Payment.lockById(paymentId)
                .filter(found -> found.merchant.id.equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("payment", paymentId));

        if (p.status != PaymentStatus.SUCCEEDED) {
            throw ApiException.conflict("payment_not_refundable",
                    "Only succeeded payments can be refunded; this one is " + p.status.json() + ".");
        }
        long remaining = p.refundable();
        if (remaining == 0) {
            throw ApiException.conflict("already_refunded", "This payment was already fully refunded.");
        }
        long value = amount == null ? remaining : amount;
        if (value > remaining) {
            throw ApiException.invalid("amount_too_large",
                    "Refund amount " + value + " exceeds the refundable " + remaining + " (amounts are in cents).");
        }

        var now = Instant.now();
        var r = new Refund();
        r.id = Ids.generate("re");
        r.paymentId = p.id;
        r.amount = value;
        r.reason = reason;
        r.createdAt = now;
        switch (p.refundBehavior) {
            case INSTANT -> r.succeed(now);
            case DELAYED -> pending(r, now.plus(delayedBy));
            case FAILS -> pending(r, now.plus(failsAfter));
        }
        r.persist();

        p.amountRefunded += value;
        p.updatedAt = now;

        announce(r, p);
        return view(r, p);
    }

    public List<RefundView> ofPayment(String merchantId, String paymentId) {
        Payment p = payments.findOwned(merchantId, paymentId);
        return Refund.ofPayment(p.id).stream().map(r -> view(r, p)).toList();
    }

    // ── Varredura ──

    @Scheduled(every = "${standardpay.refunds.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledSettlement() {
        settleDue(Instant.now());
    }

    /**
     * Conclui (ou faz falhar) os estornos pendentes cujo prazo passou até
     * {@code now}. Cada um na sua transação: uma falha não desfaz os outros.
     *
     * @return quantos foram resolvidos
     */
    public int settleDue(Instant now) {
        List<String> ids = QuarkusTransaction.requiringNew().call(() ->
                Refund.due(now, 200).stream().map(r -> r.id).toList());
        int settled = 0;
        for (String id : ids) {
            try {
                if (QuarkusTransaction.requiringNew().call(() -> settle(id, now))) settled++;
            } catch (RuntimeException e) {
                Log.warnf("Could not settle refund %s: %s", id, e.getMessage());
            }
        }
        return settled;
    }

    /**
     * Trava o estorno — duas varreduras não resolvem o mesmo duas vezes — e o
     * pagamento, cuja soma de estornado muda se o estorno falhar.
     */
    private boolean settle(String refundId, Instant now) {
        Refund r = Refund.lockById(refundId);
        if (r.status != Refund.Status.PENDING) return false;
        Payment p = Payment.lockById(r.paymentId).orElseThrow();

        if (p.refundBehavior == RefundBehavior.FAILS) {
            r.fail(FAILURE_CODE, "The card was canceled or expired before the refund reached it.", now);
            p.amountRefunded -= r.amount;
        } else {
            r.succeed(now);
        }
        p.updatedAt = now;
        announce(r, p);
        return true;
    }

    // ── Internos ──

    private static void pending(Refund r, Instant settlesAt) {
        r.status = Refund.Status.PENDING;
        r.settlesAt = settlesAt;
    }

    /** Webhook e telemetria do estado em que o estorno acabou de ficar. */
    private void announce(Refund r, Payment p) {
        Kind kind = switch (r.status) {
            case PENDING -> Kind.PENDING;
            case SUCCEEDED -> Kind.SUCCEEDED;
            case FAILED -> Kind.FAILED;
        };
        webhooks.publish(p.merchant, "refund." + r.status.name().toLowerCase(), view(r, p));
        events.fire(new RefundEvent(kind, r.id, p.id, r.amount, p.currency, p.refundable() == 0, r.failureCode));
    }

    private RefundView view(Refund r, Payment p) {
        return new RefundView(r.id, r.paymentId, r.amount, r.reason, r.status.name().toLowerCase(),
                r.failureCode, r.failureMessage, r.createdAt, r.settledAt, payments.view(p));
    }
}
