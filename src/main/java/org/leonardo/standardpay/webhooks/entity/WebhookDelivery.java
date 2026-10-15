package org.leonardo.standardpay.webhooks.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.leonardo.standardpay.merchants.entity.Merchant;

import java.time.Instant;
import java.util.List;

/**
 * Um evento a entregar ao lojista — e o histórico de cada tentativa.
 *
 * O id é o id do evento ({@code evt_…}) que vai no corpo: o lojista o usa para
 * ignorar repetições, já que uma entrega pode chegar mais de uma vez quando a
 * resposta dele se perde no caminho.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery extends PanacheEntityBase {

    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    public String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id")
    public Merchant merchant;

    @Column(name = "event_type")
    public String eventType;

    /** Fixada na criação: trocar a URL depois não redireciona eventos antigos. */
    @Column(name = "target_url")
    public String targetUrl;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) // text na migration: o corpo não tem limite de tamanho
    public String payload;

    @Enumerated(EnumType.STRING)
    public Status status;

    public int attempts;

    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "last_status_code")
    public Integer lastStatusCode;

    @Column(name = "last_error")
    public String lastError;

    @Column(name = "delivered_at")
    public Instant deliveredAt;

    @Column(name = "created_at")
    public Instant createdAt;

    public static List<WebhookDelivery> due(Instant now, int limit) {
        return find("status = ?1 and nextAttemptAt <= ?2 order by nextAttemptAt", Status.PENDING, now)
                .page(0, limit)
                .list();
    }

    /** Na fila, vencidas ou esperando a próxima tentativa — o que ainda vai ser enviado. */
    public static long countWaiting() {
        return count("status", Status.PENDING);
    }

    public static List<WebhookDelivery> recentOf(String merchantId, int limit) {
        return find("merchant.id = ?1 order by createdAt desc", merchantId).page(0, limit).list();
    }
}
