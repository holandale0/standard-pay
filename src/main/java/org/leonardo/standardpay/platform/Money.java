package org.leonardo.standardpay.platform;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

/** Valores em centavos, do jeito que uma pessoa lê e digita — para as páginas em português. */
public final class Money {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Money() {
    }

    /** 19500 em BRL vira "R$ 195,00". */
    public static String format(long cents, String currency) {
        var nf = NumberFormat.getCurrencyInstance(PT_BR);
        try {
            nf.setCurrency(Currency.getInstance(currency));
        } catch (IllegalArgumentException e) {
            return currency + " " + String.format(PT_BR, "%.2f", cents / 100.0);
        }
        // O formato pt-BR separa "R$" do número com espaço não separável (U+00A0).
        return nf.format(cents / 100.0).replace(' ', ' ');
    }

    /**
     * O que alguém digitou num campo de valor, em centavos: "50", "50,00",
     * "R$ 1.234,56" e "1234.56" valem. Vazio, ou algo que não é um valor
     * positivo com até dois decimais, não.
     */
    public static Optional<Long> parse(String typed) {
        if (typed == null) return Optional.empty();
        String s = typed.replace("R$", "").replaceAll("\\s", "");
        if (s.isEmpty()) return Optional.empty();
        // Com vírgula, ela é o decimal e os pontos separam milhares (1.234,56).
        if (s.contains(",")) s = s.replace(".", "").replace(',', '.');
        try {
            BigDecimal value = new BigDecimal(s);
            if (value.signum() <= 0 || value.scale() > 2) return Optional.empty();
            return Optional.of(value.movePointRight(2).longValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
    }
}
