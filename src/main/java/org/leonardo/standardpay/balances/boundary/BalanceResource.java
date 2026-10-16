package org.leonardo.standardpay.balances.boundary;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.balances.control.Ledger;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;

import java.time.Instant;
import java.util.List;

/** A conta do próprio lojista: o que sobrou das cobranças depois das partes dos recebedores. */
@Path("/v1/balance")
@Tag(name = "Balances")
public class BalanceResource {

    @Inject
    Ledger ledger;

    @Inject
    AuthenticatedMerchant authenticated;

    @GET
    @Operation(summary = "Your balance, available and pending, per currency")
    public BalanceViews.Balance get() {
        String merchantId = authenticated.id();
        return new BalanceViews.Balance(merchantId, ledger.balance(merchantId, null, Instant.now()));
    }

    @GET
    @Path("/transactions")
    @Operation(summary = "Your statement, newest first")
    public List<BalanceViews.Transaction> transactions(@QueryParam("limit") Integer limit) {
        return BalanceViews.transactions(ledger.transactions(authenticated.id(), null, BalanceViews.limit(limit)));
    }
}
