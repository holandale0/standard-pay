package org.leonardo.standardpay.webhooks.control;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Assinatura dos webhooks, no mesmo formato do Stripe:
 *
 * <pre>StandardPay-Signature: t=1760000000,v1=5257a869e7...</pre>
 *
 * {@code v1} é o HMAC-SHA256, com o segredo do lojista, de {@code "t.corpo"}.
 * O timestamp entra na conta para que uma requisição capturada não possa ser
 * reenviada dias depois: o lojista recusa assinaturas velhas demais.
 *
 * Como verificar do lado do lojista (Node):
 * <pre>
 * const [t, v1] = header.split(',').map(p =&gt; p.split('=')[1]);
 * const esperado = crypto.createHmac('sha256', segredo).update(`${t}.${corpoCru}`).digest('hex');
 * const ok = crypto.timingSafeEqual(Buffer.from(esperado), Buffer.from(v1));
 * </pre>
 */
public final class Signatures {

    public static final String HEADER = "StandardPay-Signature";

    private Signatures() {
    }

    public static String header(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available in the JDK", e);
        }
    }
}
