package org.leonardo.standardpay.dashboard.boundary;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Cabeçalhos de segurança das páginas do painel.
 *
 * Nenhum site pode exibir o painel dentro de um frame — seria a armadilha
 * clássica para fazer o lojista clicar em "Estornar" sem ver. E nada do painel
 * fica no cache: saldos e extratos não podem reaparecer pelo botão "voltar"
 * depois de sair.
 */
public class PanelHeaders {

    @ServerResponseFilter
    public void secure(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath().replaceFirst("^/", "");
        if (!path.equals("painel") && !path.startsWith("painel/")) return;
        var headers = response.getHeaders();
        headers.putSingle("X-Frame-Options", "DENY");
        headers.putSingle("Content-Security-Policy", "frame-ancestors 'none'");
        headers.putSingle("Cache-Control", "no-store");
        headers.putSingle("Referrer-Policy", "same-origin");
    }
}
