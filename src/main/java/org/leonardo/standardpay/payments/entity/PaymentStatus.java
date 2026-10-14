package org.leonardo.standardpay.payments.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * <pre>
 *            ┌──► SUCCEEDED   (pagador pagou)
 * PENDING ───┼──► CANCELED    (lojista ou pagador desistiu)
 *            └──► EXPIRED     (ninguém pagou a tempo)
 * </pre>
 *
 * Cartão recusado não muda o estado: a cobrança continua PENDING e o pagador
 * pode tentar outro cartão, como em qualquer checkout de verdade. O estorno
 * também não muda — um pagamento estornado continua tendo sido pago; o que
 * muda é {@code amountRefunded}.
 */
public enum PaymentStatus {
    PENDING, SUCCEEDED, CANCELED, EXPIRED;

    /** No JSON, em minúsculas, como as outras APIs de pagamento. */
    @JsonValue
    public String json() {
        return name().toLowerCase();
    }
}
