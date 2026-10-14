package org.leonardo.standardpay.webhooks.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.platform.Ids;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.time.Instant;

/**
 * Enfileira eventos para o lojista.
 *
 * Exige uma transação em andamento ({@code MANDATORY}): o evento tem de ser
 * gravado junto com a mudança que ele anuncia. Chamado fora de uma, falha na
 * hora — melhor que um aviso de pagamento que pode existir sem o pagamento.
 */
@ApplicationScoped
public class WebhookPublisher {

    /** O corpo de todo webhook. */
    public record Event(String id, String type, Instant createdAt, Object data) {
    }

    @Inject
    ObjectMapper json;

    @Transactional(Transactional.TxType.MANDATORY)
    public void publish(Merchant merchant, String type, Object data) {
        // Sem URL cadastrada não há para onde mandar; não faz sentido guardar.
        if (merchant.webhookUrl == null) return;

        var now = Instant.now();
        var delivery = new WebhookDelivery();
        delivery.id = Ids.generate("evt");
        delivery.merchant = merchant;
        delivery.eventType = type;
        delivery.targetUrl = merchant.webhookUrl;
        delivery.payload = serialize(new Event(delivery.id, type, now, data));
        delivery.status = WebhookDelivery.Status.PENDING;
        delivery.attempts = 0;
        delivery.nextAttemptAt = now;
        delivery.createdAt = now;
        delivery.persist();
    }

    private String serialize(Event event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize webhook " + event.type(), e);
        }
    }
}
