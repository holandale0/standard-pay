package org.leonardo.standardpay.platform;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/**
 * Declara na especificação OpenAPI como a API se autentica.
 *
 * Sem isto o Swagger UI não mostra o botão "Authorize", e quem experimenta a
 * API por ele recebe 401 em tudo sem entender por quê. A exigência vale para
 * a API inteira; o cadastro de lojista, que é de onde a chave sai, a remove.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "StandardPay API",
                version = "1.0",
                description = "Simulador de plataforma de pagamentos. Nenhum valor real é movimentado. "
                        + "Crie um lojista em POST /v1/merchants, copie a apiKey e use-a em Authorize.",
                license = @License(name = "MIT", url = "https://opensource.org/licenses/MIT")),
        security = @SecurityRequirement(name = OpenApiConfig.API_KEY))
@SecurityScheme(
        securitySchemeName = OpenApiConfig.API_KEY,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        description = "A chave do lojista, no formato sk_test_…")
public class OpenApiConfig extends Application {

    public static final String API_KEY = "apiKey";
}
