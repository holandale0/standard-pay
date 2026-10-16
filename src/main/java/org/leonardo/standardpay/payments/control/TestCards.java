package org.leonardo.standardpay.payments.control;

import org.leonardo.standardpay.payments.entity.RefundBehavior;

import java.util.Map;

/**
 * Cartões de teste com resultado conhecido, como no sandbox do Stripe.
 *
 * É o que torna uma demonstração reproduzível: quem apresenta sabe que
 * 4000 0000 0000 9995 vai dar "saldo insuficiente", sempre. Qualquer outro
 * número válido (que passe no dígito verificador) é aprovado.
 *
 * Alguns cartões aprovados também decidem o que acontece com os estornos
 * depois — os mesmos números do Stripe, para quem já conhece.
 */
public final class TestCards {

    /** @param refunds como os estornos do pagamento vão se comportar; só faz sentido se aprovado */
    public record Outcome(boolean approved, String code, String message, RefundBehavior refunds) {
        static Outcome approve(RefundBehavior refunds) {
            return new Outcome(true, null, null, refunds);
        }

        static Outcome decline(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    /** Número → recusa. Os de aprovação garantida estão no README e na própria página. */
    public static final Map<String, Outcome> DECLINES = Map.of(
            "4000000000000002", Outcome.decline("card_declined", "The card was declined by the issuer."),
            "4000000000009995", Outcome.decline("insufficient_funds", "The card has insufficient funds."),
            "4000000000000069", Outcome.decline("expired_card", "The card has expired."),
            "4000000000000127", Outcome.decline("incorrect_cvc", "The card's security code is incorrect."));

    /** Número → aprovado, com estornos que não concluem na hora. */
    public static final Map<String, RefundBehavior> SLOW_REFUNDS = Map.of(
            "4000000000007726", RefundBehavior.DELAYED,
            "4000000000005126", RefundBehavior.FAILS);

    private TestCards() {
    }

    public static Outcome evaluate(String number) {
        String digits = number == null ? "" : number.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{13,19}") || !luhn(digits)) {
            return Outcome.decline("invalid_number", "The card number is invalid.");
        }
        var declined = DECLINES.get(digits);
        if (declined != null) return declined;
        return Outcome.approve(SLOW_REFUNDS.getOrDefault(digits, RefundBehavior.INSTANT));
    }

    public static String last4(String number) {
        String digits = number == null ? "" : number.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    /** O dígito verificador de todo cartão de verdade: pega número digitado errado. */
    static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
