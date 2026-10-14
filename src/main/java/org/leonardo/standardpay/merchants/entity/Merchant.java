package org.leonardo.standardpay.merchants.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;

/**
 * Quem recebe pelos pagamentos: o sistema que integra o StandardPay.
 *
 * Cada lojista tem a sua chave de API, e só enxerga as próprias cobranças.
 */
@Entity
@Table(name = "merchant")
public class Merchant extends PanacheEntityBase {

    @Id
    public String id;

    public String name;

    /** SHA-256 da chave. A chave em si só é mostrada uma vez, na criação. */
    @Column(name = "api_key_hash")
    public String apiKeyHash;

    /** Para o lojista reconhecer qual chave está em uso sem ver a chave inteira. */
    @Column(name = "api_key_last4")
    public String apiKeyLast4;

    /** Para onde os eventos são enviados. Sem ele, nenhum webhook é gerado. */
    @Column(name = "webhook_url")
    public String webhookUrl;

    @Column(name = "webhook_secret")
    public String webhookSecret;

    @Column(name = "created_at")
    public Instant createdAt;

    public static Optional<Merchant> findByApiKeyHash(String hash) {
        return find("apiKeyHash", hash).firstResultOptional();
    }
}
