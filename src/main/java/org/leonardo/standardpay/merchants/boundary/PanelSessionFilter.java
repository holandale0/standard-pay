package org.leonardo.standardpay.merchants.boundary;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;
import org.leonardo.standardpay.merchants.control.PanelSessions;

import java.net.URI;

/**
 * Autenticação do painel web: o cookie da sessão aberta no login.
 *
 * Vale para tudo sob /painel, exceto a própria tela de entrada. Sem sessão
 * válida, manda para ela — o navegador não sabe o que fazer com um 401.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class PanelSessionFilter implements ContainerRequestFilter {

    public static final String COOKIE = "sp_painel";
    public static final String LOGIN = "/painel/entrar";

    @Inject
    PanelSessions sessions;

    @Inject
    AuthenticatedMerchant authenticated;

    @Inject
    PanelCsrf csrf;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath().replaceFirst("^/", "");
        if (!path.equals("painel") && !path.startsWith("painel/")) return;
        if (("/" + path).equals(LOGIN)) return;

        Cookie cookie = ctx.getCookies().get(COOKIE);
        sessions.find(cookie == null ? null : cookie.getValue()).ifPresentOrElse(
                active -> {
                    authenticated.set(active.merchant());
                    csrf.expect(active.csrfToken());
                    MDC.put("merchantId", active.merchant().id);
                },
                () -> ctx.abortWith(Response.seeOther(URI.create(LOGIN)).build()));
    }
}
