package org.leonardo.standardpay.balances.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.leonardo.standardpay.balances.entity.LedgerEntry;
import org.leonardo.standardpay.platform.Ids;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * O extrato de cada conta: quem recebeu quanto de cada cobrança, e quanto
 * devolveu em cada estorno.
 *
 * Chamado pelos pagamentos e pelos estornos dentro da transação deles
 * ({@code MANDATORY}): o lançamento é gravado junto com a mudança que o causa.
 * Não conhece pagamentos nem estornos — recebe só ids e valores.
 */
@ApplicationScoped
public class Ledger {

    /** Chave da conta do próprio lojista nos mapas de contas (TreeMap não aceita null). */
    private static final String MERCHANT_ACCOUNT = "";

    /** A parte de um recebedor numa cobrança. */
    public record Share(String recipientId, long amount) {
    }

    @ConfigProperty(name = "standardpay.balances.available-after")
    Duration availableAfter;

    /**
     * Credita cada recebedor com a sua parte, e o lojista com o que sobra. Tudo
     * pendente até {@code paidAt} + o prazo de liberação.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void paymentSucceeded(String merchantId, String paymentId, String currency, long amount,
                                 List<Share> splits, Instant paidAt) {
        Instant availableAt = paidAt.plus(availableAfter);
        long toRecipients = 0;
        for (Share s : splits) {
            add(merchantId, s.recipientId(), LedgerEntry.Type.PAYMENT, s.amount(), currency, paymentId, null,
                    availableAt, paidAt);
            toRecipients += s.amount();
        }
        if (amount > toRecipients) {
            add(merchantId, null, LedgerEntry.Type.PAYMENT, amount - toRecipients, currency, paymentId, null,
                    availableAt, paidAt);
        }
    }

    /**
     * Debita o estorno de cada conta na proporção do que ela recebeu da cobrança.
     *
     * A proporção é calculada sobre o total estornado até aqui, e não só sobre
     * este estorno: o débito é a diferença entre o que a conta já devia ter
     * devolvido e o que devolveu. Assim os centavos de arredondamento não se
     * acumulam — três estornos de R$ 0,01 não somem com um centavo de ninguém —
     * e, estornada a cobrança inteira, cada conta devolveu exatamente o que
     * recebeu.
     *
     * @param refundedSoFar o total estornado da cobrança, já contando este
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void refunded(String paymentId, String refundId, long refundedSoFar, Instant now) {
        List<LedgerEntry> entries = LedgerEntry.ofPayment(paymentId);
        if (entries.isEmpty()) return;
        LedgerEntry any = entries.get(0);

        Map<String, Long> shares = new TreeMap<>();
        Map<String, Long> returned = new TreeMap<>();
        for (LedgerEntry e : entries) {
            String account = Objects.requireNonNullElse(e.recipientId, MERCHANT_ACCOUNT);
            if (e.type == LedgerEntry.Type.PAYMENT) {
                shares.merge(account, e.amount, Long::sum);
            } else {
                returned.merge(account, -e.amount, Long::sum);
            }
        }

        Map<String, Long> due = apportion(shares, refundedSoFar);
        for (var account : due.entrySet()) {
            long debit = account.getValue() - returned.getOrDefault(account.getKey(), 0L);
            if (debit == 0) continue;
            String recipientId = account.getKey().equals(MERCHANT_ACCOUNT) ? null : account.getKey();
            add(any.merchantId, recipientId, LedgerEntry.Type.REFUND, -debit, any.currency, paymentId, refundId,
                    now, now);
        }
    }

    /** O estorno falhou: cada conta recebe de volta o que ele tinha tirado dela. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void refundFailed(String refundId, Instant now) {
        for (LedgerEntry e : LedgerEntry.ofRefund(refundId)) {
            if (e.type != LedgerEntry.Type.REFUND) continue;
            add(e.merchantId, e.recipientId, LedgerEntry.Type.REFUND_FAILURE, -e.amount, e.currency, e.paymentId,
                    refundId, now, now);
        }
    }

    public List<LedgerEntry.Totals> balance(String merchantId, String recipientId, Instant now) {
        return LedgerEntry.totalsOf(merchantId, recipientId, now);
    }

    public List<LedgerEntry> transactions(String merchantId, String recipientId, int limit) {
        return LedgerEntry.recentOf(merchantId, recipientId, limit);
    }

    /**
     * Divide {@code total} entre as contas na proporção de {@code shares}, em
     * centavos inteiros e somando exatamente {@code total}: cada conta fica com
     * a parte inteira, e os centavos que sobram vão para as maiores frações
     * (método de Hamilton). No empate, a ordem das chaves decide — sempre a mesma.
     */
    static Map<String, Long> apportion(Map<String, Long> shares, long total) {
        long whole = shares.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> result = new TreeMap<>();
        List<Map.Entry<String, Long>> remainders = new ArrayList<>();
        long given = 0;
        for (var share : shares.entrySet()) {
            long product = share.getValue() * total;
            result.put(share.getKey(), product / whole);
            remainders.add(Map.entry(share.getKey(), product % whole));
            given += product / whole;
        }
        remainders.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        for (int i = 0; i < total - given; i++) {
            result.merge(remainders.get(i).getKey(), 1L, Long::sum);
        }
        return result;
    }

    private static void add(String merchantId, String recipientId, LedgerEntry.Type type, long amount,
                            String currency, String paymentId, String refundId, Instant availableAt, Instant now) {
        var e = new LedgerEntry();
        e.id = Ids.generate("txn");
        e.merchantId = merchantId;
        e.recipientId = recipientId;
        e.type = type;
        e.amount = amount;
        e.currency = currency;
        e.paymentId = paymentId;
        e.refundId = refundId;
        e.availableAt = availableAt;
        e.createdAt = now;
        e.persist();
    }
}
