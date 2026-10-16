package org.leonardo.standardpay.refunds.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Page;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * Devolução, total ou parcial, de um pagamento aprovado.
 *
 * <pre>
 *  (cartão comum, PIX) ──────────────► SUCCEEDED
 *  (cartões de teste)  ──► PENDING ──┬► SUCCEEDED   depois do prazo
 *                                    └► FAILED      o valor volta a ser estornável
 * </pre>
 */
@Entity
@Table(name = "refund")
public class Refund extends PanacheEntityBase {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    public String id;

    @Column(name = "payment_id")
    public String paymentId;

    public long amount;

    public String reason;

    @Enumerated(EnumType.STRING)
    public Status status;

    @Column(name = "failure_code")
    public String failureCode;

    @Column(name = "failure_message")
    public String failureMessage;

    /** Quando o pendente se resolve; nulo nos que nasceram resolvidos. */
    @Column(name = "settles_at")
    public Instant settlesAt;

    @Column(name = "settled_at")
    public Instant settledAt;

    @Column(name = "created_at")
    public Instant createdAt;

    public void succeed(Instant now) {
        this.status = Status.SUCCEEDED;
        this.settledAt = now;
    }

    public void fail(String code, String message, Instant now) {
        this.status = Status.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.settledAt = now;
    }

    public static List<Refund> ofPayment(String paymentId) {
        return list("paymentId = ?1 order by createdAt", paymentId);
    }

    public static Refund lockById(String id) {
        return findById(id, LockModeType.PESSIMISTIC_WRITE);
    }

    public static List<Refund> due(Instant now, int limit) {
        return find("status = ?1 and settlesAt <= ?2 order by settlesAt", Status.PENDING, now)
                .page(Page.ofSize(limit)).list();
    }
}
