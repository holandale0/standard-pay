package org.leonardo.standardpay.merchants.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;

/** Uma sessão aberta no painel web. O token em claro só existe no cookie do navegador. */
@Entity
@Table(name = "panel_session")
public class PanelSession extends PanacheEntityBase {

    @Id
    @Column(name = "token_hash", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    public String tokenHash;

    @Column(name = "merchant_id")
    public String merchantId;

    @Column(name = "csrf_token")
    public String csrfToken;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "expires_at")
    public Instant expiresAt;

    public static Optional<PanelSession> findValid(String tokenHash, Instant now) {
        return find("tokenHash = ?1 and expiresAt > ?2", tokenHash, now).firstResultOptional();
    }

    public static long deleteExpired(Instant now) {
        return delete("expiresAt <= ?1", now);
    }
}
