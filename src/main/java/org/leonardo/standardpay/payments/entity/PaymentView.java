package org.leonardo.standardpay.payments.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * O pagamento como o lojista o vê — na resposta da API e no corpo dos webhooks.
 *
 * Um formato só para os dois: o lojista que já sabe ler a resposta de
 * {@code GET /v1/payments/{id}} sabe ler o evento.
 */
public record PaymentView(
        String id,
        long amount,
        String currency,
        String description,
        Map<String, String> metadata,
        PaymentStatus status,
        String method,
        String cardLast4,
        String failureCode,
        String failureMessage,
        long amountRefunded,
        List<Split> splits,
        String checkoutUrl,
        String returnUrl,
        String cancelUrl,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt) {

    /** A parte de um recebedor, em centavos. */
    public record Split(String recipientId, long amount) {
    }

    public static PaymentView of(Payment p, String publicUrl) {
        return new PaymentView(
                p.id, p.amount, p.currency, p.description, p.metadata, p.status,
                p.method == null ? null : p.method.name().toLowerCase(),
                p.cardLast4, p.failureCode, p.failureMessage, p.amountRefunded,
                p.splits.stream().map(s -> new Split(s.recipientId, s.amount)).toList(),
                publicUrl + "/checkout/" + p.id,
                p.returnUrl, p.cancelUrl, p.expiresAt, p.paidAt, p.createdAt);
    }
}
