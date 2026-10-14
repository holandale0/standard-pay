package org.leonardo.standardpay.merchants.boundary;

import jakarta.enterprise.context.RequestScoped;
import org.leonardo.standardpay.merchants.entity.Merchant;

/**
 * O lojista dono da requisição, preenchido pelo {@link ApiKeyFilter}.
 *
 * Os recursos recebem o lojista daqui, nunca de um parâmetro: assim não há
 * como um lojista pedir os dados de outro trocando um id na URL.
 */
@RequestScoped
public class AuthenticatedMerchant {

    private Merchant merchant;

    void set(Merchant merchant) {
        this.merchant = merchant;
    }

    public Merchant get() {
        if (merchant == null) {
            throw new IllegalStateException("No authenticated merchant in this request");
        }
        return merchant;
    }

    public String id() {
        return get().id;
    }
}
