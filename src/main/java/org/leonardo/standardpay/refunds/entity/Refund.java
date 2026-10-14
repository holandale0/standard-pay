package org.leonardo.standardpay.refunds.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * Devolução, total ou parcial, de um pagamento aprovado.
 *
 * Numa plataforma real o estorno leva dias e pode falhar; aqui é instantâneo
 * e sempre dá certo. O status existe mesmo assim, para o formato da resposta
 * não mudar no dia em que a simulação de falha entrar.
 */
@Entity
@Table(name = "refund")
public class Refund extends PanacheEntityBase {

    public enum Status { SUCCEEDED }

    @Id
    public String id;

    @Column(name = "payment_id")
    public String paymentId;

    public long amount;

    public String reason;

    @Enumerated(EnumType.STRING)
    public Status status;

    @Column(name = "created_at")
    public Instant createdAt;

    public static List<Refund> ofPayment(String paymentId) {
        return list("paymentId = ?1 order by createdAt", paymentId);
    }
}
