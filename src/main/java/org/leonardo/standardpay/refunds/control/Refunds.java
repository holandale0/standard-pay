package org.leonardo.standardpay.refunds.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.leonardo.standardpay.payments.control.PaymentProcessor;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;
import org.leonardo.standardpay.payments.entity.PaymentView;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Ids;
import org.leonardo.standardpay.refunds.control.RefundTelemetry.RefundEvent;
import org.leonardo.standardpay.refunds.entity.Refund;
import org.leonardo.standardpay.webhooks.control.WebhookPublisher;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class Refunds {

    /** O estorno e o pagamento depois dele — o webhook leva os dois. */
    public record RefundView(String id, String paymentId, long amount, String reason, String status,
                             Instant createdAt, PaymentView payment) {
    }

    @Inject
    PaymentProcessor payments;

    @Inject
    WebhookPublisher webhooks;

    @Inject
    Event<RefundEvent> events;

    /**
     * Estorna {@code amount} centavos; sem valor, estorna tudo o que resta.
     *
     * Trava o pagamento antes de somar: dois estornos simultâneos de R$ 100
     * num pagamento de R$ 150 não podem passar os dois.
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
        r.status = Refund.Status.SUCCEEDED;
        r.createdAt = now;
        r.persist();

        p.amountRefunded += value;
        p.updatedAt = now;

        var view = view(r, p);
        webhooks.publish(p.merchant, "refund.succeeded", view);
        events.fire(new RefundEvent(r.id, p.id, value, p.currency, p.refundable() == 0));
        return view;
    }

    public List<RefundView> ofPayment(String merchantId, String paymentId) {
        Payment p = payments.findOwned(merchantId, paymentId);
        return Refund.ofPayment(p.id).stream().map(r -> view(r, p)).toList();
    }

    private RefundView view(Refund r, Payment p) {
        return new RefundView(r.id, r.paymentId, r.amount, r.reason, r.status.name().toLowerCase(),
                r.createdAt, payments.view(p));
    }
}
