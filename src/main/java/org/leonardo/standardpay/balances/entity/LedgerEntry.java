package org.leonardo.standardpay.balances.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Page;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * Uma linha do extrato de uma conta: a do lojista ({@code recipientId} nulo) ou
 * a de um recebedor. Valor com sinal — crédito positivo, débito negativo.
 *
 * Nunca é alterada nem apagada: uma correção é outra linha. O saldo é a soma
 * delas, e {@code availableAt} separa o disponível do pendente.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends PanacheEntityBase {

    public enum Type {
        /** A parte da conta numa cobrança paga; fica pendente até availableAt. */
        PAYMENT,
        /** A parte da conta num estorno; sai na hora. */
        REFUND,
        /** O estorno falhou: devolve o que ele tinha tirado. */
        REFUND_FAILURE
    }

    @Id
    public String id;

    @Column(name = "merchant_id")
    public String merchantId;

    @Column(name = "recipient_id")
    public String recipientId;

    @Enumerated(EnumType.STRING)
    public Type type;

    public long amount;

    @Column(length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    public String currency;

    @Column(name = "payment_id")
    public String paymentId;

    @Column(name = "refund_id")
    public String refundId;

    @Column(name = "available_at")
    public Instant availableAt;

    @Column(name = "created_at")
    public Instant createdAt;

    /** Saldo de uma conta numa moeda. */
    public record Totals(String currency, long available, long pending) {
    }

    public static List<LedgerEntry> ofPayment(String paymentId) {
        return list("paymentId = ?1 order by createdAt, id", paymentId);
    }

    public static List<LedgerEntry> ofRefund(String refundId) {
        return list("refundId = ?1", refundId);
    }

    public static List<LedgerEntry> recentOf(String merchantId, String recipientId, int limit) {
        var query = recipientId == null
                ? find("merchantId = ?1 and recipientId is null order by createdAt desc, id", merchantId)
                : find("merchantId = ?1 and recipientId = ?2 order by createdAt desc, id", merchantId, recipientId);
        return query.page(Page.ofSize(limit)).list();
    }

    public static List<Totals> totalsOf(String merchantId, String recipientId, Instant now) {
        String account = recipientId == null ? "e.recipientId is null" : "e.recipientId = :recipient";
        var query = getEntityManager().createQuery("""
                        select e.currency,
                               sum(case when e.availableAt <= :now then e.amount else 0 end),
                               sum(case when e.availableAt > :now then e.amount else 0 end)
                        from LedgerEntry e
                        where e.merchantId = :merchant and %s
                        group by e.currency
                        order by e.currency""".formatted(account), Object[].class)
                .setParameter("merchant", merchantId)
                .setParameter("now", now);
        if (recipientId != null) query.setParameter("recipient", recipientId);
        return query.getResultList().stream()
                .map(row -> new Totals((String) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                .toList();
    }
}
