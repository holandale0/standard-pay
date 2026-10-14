package org.leonardo.standardpay.merchants.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Ids;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class MerchantRegistry {

    /** O lojista recém-criado e a chave em claro — a única vez que ela existe fora do cliente. */
    public record Registration(Merchant merchant, String apiKey) {
    }

    @Transactional
    public Registration register(String name, String webhookUrl) {
        String apiKey = Credentials.newApiKey();

        var merchant = new Merchant();
        merchant.id = Ids.generate("mer");
        merchant.name = name.trim();
        merchant.apiKeyHash = Credentials.hash(apiKey);
        merchant.apiKeyLast4 = apiKey.substring(apiKey.length() - 4);
        merchant.webhookUrl = validUrl(webhookUrl);
        merchant.webhookSecret = Credentials.newWebhookSecret();
        merchant.createdAt = Instant.now();
        merchant.persist();

        return new Registration(merchant, apiKey);
    }

    public Optional<Merchant> authenticate(String apiKey) {
        if (apiKey == null || !apiKey.startsWith(Credentials.API_KEY_PREFIX)) return Optional.empty();
        return Merchant.findByApiKeyHash(Credentials.hash(apiKey));
    }

    @Transactional
    public Merchant updateWebhookUrl(String merchantId, String webhookUrl) {
        Merchant merchant = Merchant.findById(merchantId);
        merchant.webhookUrl = validUrl(webhookUrl);
        return merchant;
    }

    /** Novo segredo; o antigo deixa de valer na hora. */
    @Transactional
    public Merchant rotateWebhookSecret(String merchantId) {
        Merchant merchant = Merchant.findById(merchantId);
        merchant.webhookSecret = Credentials.newWebhookSecret();
        return merchant;
    }

    /**
     * Só http e https. Outro esquema (file:, ftp:) faria o StandardPay tentar
     * abrir coisas que não são endpoints.
     */
    static String validUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("invalid_url", "'" + url + "' is not a valid http(s) URL.");
        }
    }
}
