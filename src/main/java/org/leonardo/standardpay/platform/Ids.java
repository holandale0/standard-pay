package org.leonardo.standardpay.platform;

import java.security.SecureRandom;

/**
 * Identificadores com prefixo de tipo: {@code pay_…}, {@code re_…}, {@code evt_…}.
 *
 * O prefixo diz o que o id é só de olhar para ele, num log ou num chamado de
 * suporte. A parte aleatória vem do {@link SecureRandom} porque o id do
 * pagamento é também a chave da página de checkout: quem o adivinhasse veria
 * a cobrança de outra pessoa.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private Ids() {
    }

    /** 24 caracteres em base 62: cerca de 143 bits, impossível de adivinhar. */
    public static String generate(String prefix) {
        return prefix + "_" + random(24);
    }

    public static String random(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
