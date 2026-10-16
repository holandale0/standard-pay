package org.leonardo.standardpay.payments.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A parte de um recebedor numa cobrança, em centavos. O que sobra é do lojista. */
@Embeddable
public class PaymentSplit {

    @Column(name = "recipient_id")
    public String recipientId;

    public long amount;

    public PaymentSplit() {
    }

    public PaymentSplit(String recipientId, long amount) {
        this.recipientId = recipientId;
        this.amount = amount;
    }
}
