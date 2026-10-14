package org.leonardo.standardpay.payments.control;

import java.util.Map;

/**
 * Cartões de teste com resultado conhecido, como no sandbox do Stripe.
 *
 * É o que torna uma demonstração reproduzível: quem apresenta sabe que
 * 4000 0000 0000 9995 vai dar "saldo insuficiente", sempre. Qualquer outro
 * número válido (que passe no dígito verificador) é aprovado.
 */
public final class TestCards {

    public record Outcome(boolean approved, String code, String message) {
        static Outcome approve() {
            return new Outcome(true, null, null);
        }

        static Outcome decline(String code, String message) {
            return new Outcome(false, code, message);
        }
    }

    /** Número → recusa. Os de aprovação garantida estão no README e na própria página. */
    public static final Map<String, Outcome> DECLINES = Map.of(
            "4000000000000002", Outcome.decline("card_declined", "The card was declined by the issuer."),
            "4000000000009995", Outcome.decline("insufficient_funds", "The card has insufficient funds."),
            "4000000000000069", Outcome.decline("expired_card", "The card has expired."),
            "4000000000000127", Outcome.decline("incorrect_cvc", "The card's security code is incorrect."));

    private TestCards() {
    }

    public static Outcome evaluate(String number) {
        String digits = number == null ? "" : number.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{13,19}") || !luhn(digits)) {
            return Outcome.decline("invalid_number", "The card number is invalid.");
        }
        return DECLINES.getOrDefault(digits, Outcome.approve());
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
