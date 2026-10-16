package org.leonardo.standardpay.merchants.boundary;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * O token anti-CSRF da sessão do painel, preenchido pelo {@link PanelSessionFilter}.
 *
 * Todo formulário do painel o leva num campo escondido. Outro site consegue
 * fazer o navegador do lojista postar para cá, com o cookie junto — mas não
 * consegue ler a página para descobrir o token.
 */
@RequestScoped
public class PanelCsrf {

    private String expected;

    void expect(String token) {
        this.expected = token;
    }

    /** Para pôr nos formulários. */
    public String token() {
        return expected;
    }

    public void check(String submitted) {
        boolean ok = expected != null && submitted != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ForbiddenException("Invalid or missing form token. Reload the page and try again.");
    }
}
