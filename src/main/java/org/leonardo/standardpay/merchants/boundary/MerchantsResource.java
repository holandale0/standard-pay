package org.leonardo.standardpay.merchants.boundary;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.merchants.control.MerchantRegistry;
import org.leonardo.standardpay.merchants.entity.Merchant;

import java.time.Instant;

@Path("/v1/merchants")
@Tag(name = "Merchants")
public class MerchantsResource {

    @Inject
    MerchantRegistry registry;

    @Inject
    AuthenticatedMerchant authenticated;

    public record CreateMerchantRequest(
            @NotBlank(message = "is required") @Size(max = 120, message = "must have at most 120 characters")
            String name,
            @Size(max = 500, message = "must have at most 500 characters")
            String webhookUrl) {
    }

    public record UpdateMerchantRequest(
            @Size(max = 500, message = "must have at most 500 characters")
            String webhookUrl) {
    }

    public record MerchantView(String id, String name, String apiKeyLast4, String webhookUrl,
                               String webhookSecret, Instant createdAt) {
        static MerchantView of(Merchant m) {
            return new MerchantView(m.id, m.name, m.apiKeyLast4, m.webhookUrl, m.webhookSecret, m.createdAt);
        }
    }

    /** A resposta do cadastro é a única que traz a chave em claro. */
    public record MerchantCreated(String id, String name, String apiKey, String webhookUrl,
                                  String webhookSecret, Instant createdAt) {
    }

    @POST
    @SecurityRequirementsSet // vazio: o cadastro é de onde a chave sai
    @Operation(summary = "Create a merchant account",
            description = "Returns the API key. It is shown only once — store it safely.")
    public Response create(@Valid @NotNull CreateMerchantRequest request) {
        var r = registry.register(request.name(), request.webhookUrl());
        var m = r.merchant();
        return Response.status(201)
                .entity(new MerchantCreated(m.id, m.name, r.apiKey(), m.webhookUrl, m.webhookSecret, m.createdAt))
                .build();
    }

    @GET
    @Path("/me")
    @Operation(summary = "The merchant that owns the API key")
    public MerchantView me() {
        return MerchantView.of(authenticated.get());
    }

    @PATCH
    @Path("/me")
    @Operation(summary = "Change the webhook URL", description = "Send null to stop receiving webhooks.")
    public MerchantView update(@Valid @NotNull UpdateMerchantRequest request) {
        return MerchantView.of(registry.updateWebhookUrl(authenticated.id(), request.webhookUrl()));
    }

    @POST
    @Path("/me/webhook-secret")
    @Operation(summary = "Rotate the webhook signing secret", description = "The previous secret stops working immediately.")
    public MerchantView rotateWebhookSecret() {
        return MerchantView.of(registry.rotateWebhookSecret(authenticated.id()));
    }
}
