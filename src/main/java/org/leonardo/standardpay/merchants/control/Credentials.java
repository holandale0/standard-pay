package org.leonardo.standardpay.merchants.control;

import org.leonardo.standardpay.platform.Ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Chaves de API e segredos de webhook.
 *
 * O prefixo {@code sk_test_} segue a convenção do Stripe e deixa claro, em
 * qualquer log ou arquivo de configuração, que aquilo é credencial de
 * simulação — nunca de dinheiro de verdade.
 */
public final class Credentials {

    public static final String API_KEY_PREFIX = "sk_test_";
    public static final String WEBHOOK_SECRET_PREFIX = "whsec_";

    private Credentials() {
    }

    public static String newApiKey() {
        return API_KEY_PREFIX + Ids.random(32);
    }

    public static String newWebhookSecret() {
        return WEBHOOK_SECRET_PREFIX + Ids.random(32);
    }

    /**
     * SHA-256 simples, sem sal nem custo, de propósito: a chave já é aleatória
     * com 190 bits. Bcrypt existe para senhas escolhidas por gente, que se
     * adivinham por dicionário; aqui só deixaria cada requisição mais lenta.
     */
    public static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
