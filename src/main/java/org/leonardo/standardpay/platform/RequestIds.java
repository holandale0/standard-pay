package org.leonardo.standardpay.platform;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import java.util.regex.Pattern;

/**
 * Um identificador por requisição, em toda linha de log e no cabeçalho
 * {@code X-Request-Id} da resposta.
 *
 * É o que o lojista informa ao suporte — "a cobrança do X-Request-Id
 * req_8PA7… deu erro" — e o que leva direto às linhas daquela requisição.
 * Se o lojista já mandar o seu, ele é mantido, e a correlação atravessa os
 * dois sistemas.
 */
public class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /**
     * O que aceitamos do cliente. Sem esta restrição, um id com quebra de
     * linha forjaria linhas inteiras no log de quem o lesse.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** Antes do roteamento, para valer também nas respostas de erro de rota e de autenticação. */
    @ServerRequestFilter(preMatching = true)
    public void assign(ContainerRequestContext request) {
        String sent = request.getHeaderString(HEADER);
        String id = sent != null && SAFE.matcher(sent).matches() ? sent : Ids.generate("req");
        request.setProperty(MDC_KEY, id);
        MDC.put(MDC_KEY, id);
    }

    @ServerResponseFilter
    public void echo(ContainerRequestContext request, ContainerResponseContext response) {
        Object id = request.getProperty(MDC_KEY);
        if (id != null) response.getHeaders().putSingle(HEADER, id);
    }

    /** O id da requisição em curso, ou nulo fora de uma (no agendador, por exemplo). */
    public static String current() {
        Object id = MDC.get(MDC_KEY);
        return id == null ? null : id.toString();
    }
}
