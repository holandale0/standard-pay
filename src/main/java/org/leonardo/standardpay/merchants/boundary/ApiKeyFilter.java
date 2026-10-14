package org.leonardo.standardpay.merchants.boundary;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.leonardo.standardpay.merchants.control.MerchantRegistry;
import org.leonardo.standardpay.platform.ErrorMappers.ErrorBody;

/**
 * Autenticação da API: {@code Authorization: Bearer sk_test_…}.
 *
 * Vale para tudo sob /v1, exceto o cadastro de lojista — que é justamente de
 * onde a chave sai. A página de checkout fica fora: quem a abre é o pagador,
 * que não tem chave nenhuma; o que a protege é o id impossível de adivinhar.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {

    private static final String BEARER = "Bearer ";

    @Inject
    MerchantRegistry registry;

    @Inject
    AuthenticatedMerchant authenticated;

    @Override
    public void filter(ContainerRequestContext ctx) {
        // A especificação não fixa se o caminho vem com a barra inicial.
        String path = ctx.getUriInfo().getPath().replaceFirst("^/", "");
        if (!path.startsWith("v1/")) return;
        if (path.equals("v1/merchants") && "POST".equals(ctx.getMethod())) return;

        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        String apiKey = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;

        registry.authenticate(apiKey).ifPresentOrElse(
                authenticated::set,
                () -> ctx.abortWith(Response.status(401)
                        .type(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                        .entity(new ErrorBody(new ErrorBody.Error("invalid_api_key",
                                apiKey == null
                                        ? "Missing API key. Send 'Authorization: Bearer sk_test_...'."
                                        : "Invalid API key.")))
                        .build()));
    }
}
