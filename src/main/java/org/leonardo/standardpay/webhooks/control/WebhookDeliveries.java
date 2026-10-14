package org.leonardo.standardpay.webhooks.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.time.Instant;
import java.util.List;

/** O histórico de entregas do lojista, e o pedido de reenvio. */
@ApplicationScoped
public class WebhookDeliveries {

    public List<WebhookDelivery> recent(String merchantId, int limit) {
        return WebhookDelivery.recentOf(merchantId, limit);
    }

    /**
     * Volta a entrega para a fila, com o contador zerado — vale também para as
     * que já tinham desistido. Sai na próxima rodada do dispatcher.
     */
    @Transactional
    public WebhookDelivery retry(String merchantId, String deliveryId) {
        WebhookDelivery d = WebhookDelivery.findById(deliveryId);
        // De outro lojista responde como inexistente: não confirma nem que o id existe.
        if (d == null || !d.merchant.id.equals(merchantId)) {
            throw ApiException.notFound("webhook delivery", deliveryId);
        }
        d.status = WebhookDelivery.Status.PENDING;
        d.attempts = 0;
        d.nextAttemptAt = Instant.now();
        return d;
    }
}
