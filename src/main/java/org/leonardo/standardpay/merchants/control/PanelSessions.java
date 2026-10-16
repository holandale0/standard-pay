package org.leonardo.standardpay.merchants.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.merchants.entity.PanelSession;
import org.leonardo.standardpay.platform.Ids;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Entrar e sair do painel web.
 *
 * A chave de API é a credencial — o lojista não ganha uma senha a mais para
 * guardar. Ela é conferida uma vez, no login; dali em diante vale o token da
 * sessão, que expira sozinho e pode ser encerrado sem trocar a chave.
 */
@ApplicationScoped
public class PanelSessions {

    /** A sessão aberta e o token em claro — que vai para o cookie, e só para lá. */
    public record Opened(String token, Merchant merchant) {
    }

    /** Quem é o dono da sessão, e o token que os formulários precisam devolver. */
    public record Active(Merchant merchant, String csrfToken) {
    }

    @Inject
    MerchantRegistry registry;

    @ConfigProperty(name = "standardpay.panel.session-lifetime")
    Duration lifetime;

    @Transactional
    public Optional<Opened> open(String apiKey) {
        var now = Instant.now();
        // Faxina de passagem: o login é raro o bastante para não pesar, e a tabela
        // não cresce para sempre.
        PanelSession.deleteExpired(now);
        return registry.authenticate(apiKey).map(merchant -> {
            String token = Ids.random(40);
            var s = new PanelSession();
            s.tokenHash = Credentials.hash(token);
            s.merchantId = merchant.id;
            s.csrfToken = Ids.random(32);
            s.createdAt = now;
            s.expiresAt = now.plus(lifetime);
            s.persist();
            return new Opened(token, merchant);
        });
    }

    public Optional<Active> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return PanelSession.findValid(Credentials.hash(token), Instant.now())
                .map(s -> new Active(Merchant.findById(s.merchantId), s.csrfToken));
    }

    @Transactional
    public void close(String token) {
        if (token == null || token.isBlank()) return;
        PanelSession.deleteById(Credentials.hash(token));
    }

    public Duration lifetime() {
        return lifetime;
    }
}
