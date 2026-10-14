package org.leonardo.standardpay.webhooks.boundary;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.time.Instant;
import java.util.List;

/**
 * O que foi enviado ao lojista, e o que aconteceu em cada envio.
 *
 * Existe para depurar a integração: "o webhook chegou?" se responde aqui, sem
 * precisar acesso aos logs do StandardPay.
 */
@Path("/v1/webhook-deliveries")
@Tag(name = "Webhooks")
public class WebhookDeliveriesResource {

    @Inject
    AuthenticatedMerchant authenticated;

    public record DeliveryView(String id, String eventType, String targetUrl, String status, int attempts,
                               Integer lastStatusCode, String lastError, Instant nextAttemptAt,
                               Instant deliveredAt, Instant createdAt, String payload) {
        static DeliveryView of(WebhookDelivery d) {
            return new DeliveryView(d.id, d.eventType, d.targetUrl, d.status.name().toLowerCase(), d.attempts,
                    d.lastStatusCode, d.lastError,
                    d.status == WebhookDelivery.Status.PENDING ? d.nextAttemptAt : null,
                    d.deliveredAt, d.createdAt, d.payload);
        }
    }

    @GET
    @Operation(summary = "Recent webhook deliveries, newest first")
    public List<DeliveryView> list(@QueryParam("limit") Integer limit) {
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return WebhookDelivery.recentOf(authenticated.id(), n).stream().map(DeliveryView::of).toList();
    }

    @POST
    @Path("/{id}/retry")
    @Transactional
    @Operation(summary = "Send a delivery again on the next dispatch round",
            description = "Works for failed deliveries too; the attempt counter starts over.")
    public DeliveryView retry(@PathParam("id") String id) {
        WebhookDelivery d = WebhookDelivery.findById(id);
        if (d == null || !d.merchant.id.equals(authenticated.id())) throw ApiException.notFound("webhook delivery", id);
        d.status = WebhookDelivery.Status.PENDING;
        d.attempts = 0;
        d.nextAttemptAt = Instant.now();
        return DeliveryView.of(d);
    }
}
