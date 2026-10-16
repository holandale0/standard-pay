package org.leonardo.standardpay.balances.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Page;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quem recebe parte de uma cobrança dividida — o guia de um passeio, o vendedor
 * de um marketplace. Pertence a um lojista, e só ele o vê e o usa.
 */
@Entity
@Table(name = "recipient")
public class Recipient extends PanacheEntityBase {

    @Id
    public String id;

    @Column(name = "merchant_id")
    public String merchantId;

    public String name;

    /** Dados do lojista, devolvidos intactos — o id do guia no sistema dele, por exemplo. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, String> metadata = new HashMap<>();

    @Column(name = "created_at")
    public Instant createdAt;

    /** Recebedor de outro lojista conta como inexistente. */
    public static Optional<Recipient> findOwned(String merchantId, String id) {
        return find("id = ?1 and merchantId = ?2", id, merchantId).firstResultOptional();
    }

    public static List<Recipient> recentOf(String merchantId, int limit) {
        return find("merchantId = ?1 order by createdAt desc", merchantId).page(Page.ofSize(limit)).list();
    }
}
