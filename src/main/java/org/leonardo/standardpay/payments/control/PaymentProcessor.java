package org.leonardo.standardpay.payments.control;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;
import org.leonardo.standardpay.payments.entity.PaymentView;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Ids;
import org.leonardo.standardpay.webhooks.control.WebhookPublisher;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * As regras do pagamento: criar, pagar, recusar, cancelar, expirar.
 *
 * Toda mudança de estado publica o webhook na mesma transação, e toda
 * operação que depende do estado atual trava a linha antes de ler — dois
 * cliques simultâneos em "Pagar" não cobram duas vezes.
 */
@ApplicationScoped
public class PaymentProcessor {

    /** O que o lojista pede. Valores em centavos. */
    public record NewPayment(long amount, String currency, String description, Map<String, String> metadata,
                             String returnUrl, String cancelUrl) {
    }

    /** O desfecho de uma tentativa na página de checkout. */
    public record Attempt(Payment payment, TestCards.Outcome outcome) {
    }

    @Inject
    WebhookPublisher webhooks;

    @ConfigProperty(name = "standardpay.public-url")
    String publicUrl;

    @ConfigProperty(name = "standardpay.checkout.expires-in")
    Duration expiresIn;

    public PaymentView view(Payment p) {
        return PaymentView.of(p, publicUrl);
    }

    // ── Lojista ──

    @Transactional
    public Payment create(Merchant merchant, NewPayment request, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = Payment.findByIdempotencyKey(merchant.id, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }

        var now = Instant.now();
        var p = new Payment();
        p.id = Ids.generate("pay");
        p.merchant = merchant;
        p.amount = request.amount();
        p.currency = request.currency() == null ? "BRL" : request.currency().toUpperCase();
        p.description = request.description();
        p.metadata = request.metadata() == null ? new HashMap<>() : new HashMap<>(request.metadata());
        p.status = PaymentStatus.PENDING;
        p.returnUrl = validUrl(request.returnUrl(), "returnUrl");
        p.cancelUrl = validUrl(request.cancelUrl(), "cancelUrl");
        p.idempotencyKey = idempotencyKey;
        p.expiresAt = now.plus(expiresIn);
        p.createdAt = now;
        p.updatedAt = now;
        // Flush aqui, e não no commit: a violação da chave de idempotência precisa
        // aparecer dentro do método, como PersistenceException, para o recurso
        // tratar a corrida entre duas requisições com a mesma chave.
        p.persistAndFlush();
        return p;
    }

    public Payment findOwned(String merchantId, String paymentId) {
        Payment p = Payment.findById(paymentId);
        // Cobrança de outro lojista responde como inexistente: não confirma nem que o id existe.
        if (p == null || !p.merchant.id.equals(merchantId)) throw ApiException.notFound("payment", paymentId);
        return p;
    }

    public List<Payment> list(String merchantId, PaymentStatus status, int limit) {
        return Payment.recentOf(merchantId, status, limit);
    }

    @Transactional
    public Payment cancelByMerchant(String merchantId, String paymentId) {
        Payment p = locked(paymentId);
        if (!p.merchant.id.equals(merchantId)) throw ApiException.notFound("payment", paymentId);
        return cancel(p);
    }

    // ── Pagador (página de checkout) ──

    public Payment findForCheckout(String paymentId) {
        Payment p = Payment.findById(paymentId);
        if (p == null) throw ApiException.notFound("payment", paymentId);
        return p;
    }

    @Transactional
    public Attempt payWithCard(String paymentId, String cardNumber) {
        Payment p = locked(paymentId);
        var now = Instant.now();
        if (!p.isPayable(now)) return new Attempt(p, null);

        var outcome = TestCards.evaluate(cardNumber);
        String last4 = TestCards.last4(cardNumber);
        if (outcome.approved()) {
            p.succeed(Payment.Method.CARD, last4, now);
            webhooks.publish(p.merchant, "payment.succeeded", view(p));
        } else {
            p.decline(outcome.code(), outcome.message(), last4, now);
            webhooks.publish(p.merchant, "payment.failed", view(p));
        }
        return new Attempt(p, outcome);
    }

    /**
     * PIX não tem como ser pago de verdade aqui; o botão "simular pagamento"
     * faz o papel do banco avisando que o dinheiro caiu.
     */
    @Transactional
    public Payment payWithPix(String paymentId) {
        Payment p = locked(paymentId);
        var now = Instant.now();
        if (!p.isPayable(now)) return p;
        p.succeed(Payment.Method.PIX, null, now);
        webhooks.publish(p.merchant, "payment.succeeded", view(p));
        return p;
    }

    @Transactional
    public Payment cancelByPayer(String paymentId) {
        Payment p = locked(paymentId);
        return p.status == PaymentStatus.PENDING ? cancel(p) : p;
    }

    /** Quem abre a página depois do prazo vê "expirado" na hora, sem esperar a varredura. */
    @Transactional
    public Payment expireIfOverdue(String paymentId) {
        Payment p = locked(paymentId);
        var now = Instant.now();
        if (p.status == PaymentStatus.PENDING && !now.isBefore(p.expiresAt)) {
            p.finish(PaymentStatus.EXPIRED, now);
            webhooks.publish(p.merchant, "payment.expired", view(p));
        }
        return p;
    }

    // ── Varredura ──

    /**
     * Expira as cobranças cujo prazo passou. Cada uma na sua transação: uma
     * falha não desfaz as outras.
     *
     * @return quantas expiraram
     */
    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledExpiration() {
        expireOverdue();
    }

    public int expireOverdue() {
        List<String> ids = QuarkusTransaction.requiringNew().call(() ->
                Payment.overdue(Instant.now(), 200).stream().map(p -> p.id).toList());
        int expired = 0;
        for (String id : ids) {
            try {
                Payment p = QuarkusTransaction.requiringNew().call(() -> expireIfOverdue(id));
                if (p.status == PaymentStatus.EXPIRED) expired++;
            } catch (RuntimeException e) {
                Log.warnf("Could not expire payment %s: %s", id, e.getMessage());
            }
        }
        return expired;
    }

    // ── Internos ──

    private Payment cancel(Payment p) {
        if (p.status == PaymentStatus.CANCELED) return p;
        if (p.status != PaymentStatus.PENDING) {
            throw ApiException.conflict("payment_not_cancelable",
                    "Only pending payments can be canceled; this one is " + p.status.json() + ".");
        }
        p.finish(PaymentStatus.CANCELED, Instant.now());
        webhooks.publish(p.merchant, "payment.canceled", view(p));
        return p;
    }

    private Payment locked(String paymentId) {
        return Payment.lockById(paymentId).orElseThrow(() -> ApiException.notFound("payment", paymentId));
    }

    /** Para onde o pagador volta. Só http(s): outro esquema seria um redirecionamento perigoso. */
    private static String validUrl(String url, String field) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("invalid_url", field + " must be a valid http(s) URL.");
        }
    }
}
