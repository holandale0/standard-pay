package org.leonardo.standardpay.payments.entity;

/**
 * Como os estornos de uma cobrança se comportam. Decidido pelo cartão de teste
 * com que ela foi paga, como no sandbox do Stripe, e guardado na cobrança: o
 * estorno pode vir dias depois, e só o final do cartão não basta para saber.
 */
public enum RefundBehavior {
    /** Concluído na hora — o padrão, e o do PIX. */
    INSTANT,
    /** Fica pendente e só conclui depois de um prazo — estornos de cartão levam dias. */
    DELAYED,
    /** Fica pendente e depois falha: o cartão foi cancelado nesse meio-tempo. */
    FAILS
}
