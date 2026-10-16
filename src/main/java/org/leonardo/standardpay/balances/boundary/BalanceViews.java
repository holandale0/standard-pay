package org.leonardo.standardpay.balances.boundary;

import org.leonardo.standardpay.balances.entity.LedgerEntry;

import java.time.Instant;
import java.util.List;

/** Saldo e extrato como a API os mostra — iguais para o lojista e para os recebedores. */
public final class BalanceViews {

    private BalanceViews() {
    }

    /**
     * @param account o id do lojista ou do recebedor
     * @param balances um por moeda em que a conta já recebeu
     */
    public record Balance(String account, List<LedgerEntry.Totals> balances) {
    }

    /** @param status pending até availableAt; available depois */
    public record Transaction(String id, String type, long amount, String currency, String status,
                              String paymentId, String refundId, Instant availableAt, Instant createdAt) {

        static Transaction of(LedgerEntry e, Instant now) {
            return new Transaction(e.id, e.type.name().toLowerCase(), e.amount, e.currency,
                    e.availableAt.isAfter(now) ? "pending" : "available",
                    e.paymentId, e.refundId, e.availableAt, e.createdAt);
        }
    }

    static List<Transaction> transactions(List<LedgerEntry> entries) {
        var now = Instant.now();
        return entries.stream().map(e -> Transaction.of(e, now)).toList();
    }

    static int limit(Integer requested) {
        return requested == null ? 50 : Math.max(1, Math.min(requested, 200));
    }
}
