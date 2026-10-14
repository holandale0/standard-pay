package org.leonardo.standardpay.payments.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Page;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.leonardo.standardpay.merchants.entity.Merchant;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Uma cobrança: o lojista cria, o pagador paga (ou não) na página de checkout.
 */
@Entity
@Table(name = "payment")
public class Payment extends PanacheEntityBase {

    public enum Method { CARD, PIX }

    @Id
    public String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id")
    public Merchant merchant;

    /** Em centavos: 19500 é R$ 195,00. */
    public long amount;

    public String currency;

    public String description;

    /** Dados do lojista, devolvidos intactos — o tour-guide guarda aqui o id do passeio. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, String> metadata = new HashMap<>();

    @Enumerated(EnumType.STRING)
    public PaymentStatus status;

    @Enumerated(EnumType.STRING)
    public Method method;

    @Column(name = "card_last4")
    public String cardLast4;

    /** A última recusa, para o lojista saber por que o pagador ainda não pagou. */
    @Column(name = "failure_code")
    public String failureCode;

    @Column(name = "failure_message")
    public String failureMessage;

    @Column(name = "amount_refunded")
    public long amountRefunded;

    @Column(name = "return_url")
    public String returnUrl;

    @Column(name = "cancel_url")
    public String cancelUrl;

    @Column(name = "idempotency_key")
    public String idempotencyKey;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "paid_at")
    public Instant paidAt;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    // ── Regras ──

    /** Pendente e dentro do prazo: a única situação em que alguém ainda pode pagar. */
    public boolean isPayable(Instant now) {
        return status == PaymentStatus.PENDING && now.isBefore(expiresAt);
    }

    public long refundable() {
        return status == PaymentStatus.SUCCEEDED ? amount - amountRefunded : 0;
    }

    public void succeed(Method method, String cardLast4, Instant now) {
        this.status = PaymentStatus.SUCCEEDED;
        this.method = method;
        this.cardLast4 = cardLast4;
        this.failureCode = null;
        this.failureMessage = null;
        this.paidAt = now;
        this.updatedAt = now;
    }

    public void decline(String code, String message, String cardLast4, Instant now) {
        this.method = Method.CARD;
        this.cardLast4 = cardLast4;
        this.failureCode = code;
        this.failureMessage = message;
        this.updatedAt = now;
    }

    public void finish(PaymentStatus finalStatus, Instant now) {
        this.status = finalStatus;
        this.updatedAt = now;
    }

    // ── Consultas ──

    /** Bloqueia a linha até o fim da transação: dois cliques em "Pagar" não pagam duas vezes. */
    public static Optional<Payment> lockById(String id) {
        return Optional.ofNullable(findById(id, LockModeType.PESSIMISTIC_WRITE));
    }

    public static Optional<Payment> findByIdempotencyKey(String merchantId, String key) {
        return find("merchant.id = ?1 and idempotencyKey = ?2", merchantId, key).firstResultOptional();
    }

    public static List<Payment> recentOf(String merchantId, PaymentStatus status, int limit) {
        var query = status == null
                ? find("merchant.id = ?1 order by createdAt desc", merchantId)
                : find("merchant.id = ?1 and status = ?2 order by createdAt desc", merchantId, status);
        return query.page(Page.ofSize(limit)).list();
    }

    public static List<Payment> overdue(Instant now, int limit) {
        return find("status = ?1 and expiresAt <= ?2", PaymentStatus.PENDING, now).page(Page.ofSize(limit)).list();
    }
}
