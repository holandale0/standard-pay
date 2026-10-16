package org.leonardo.standardpay.balances.boundary;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.balances.control.Ledger;
import org.leonardo.standardpay.balances.control.Recipients;
import org.leonardo.standardpay.balances.entity.Recipient;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Path("/v1/recipients")
@Tag(name = "Recipients")
public class RecipientsResource {

    @Inject
    Recipients recipients;

    @Inject
    Ledger ledger;

    @Inject
    AuthenticatedMerchant authenticated;

    public record CreateRecipientRequest(
            @NotBlank(message = "is required")
            @Size(max = 120, message = "must have at most 120 characters")
            String name,
            @Size(max = 20, message = "must have at most 20 keys")
            Map<String, String> metadata) {
    }

    public record RecipientView(String id, String name, Map<String, String> metadata, Instant createdAt) {
        static RecipientView of(Recipient r) {
            return new RecipientView(r.id, r.name, r.metadata, r.createdAt);
        }
    }

    @POST
    @Operation(summary = "Create a recipient",
            description = "Someone who receives part of your payments. Use its id in the splits of a payment.")
    public Response create(@Valid @NotNull CreateRecipientRequest request) {
        var r = recipients.create(authenticated.id(), request.name(), request.metadata());
        return Response.status(201).entity(RecipientView.of(r)).build();
    }

    @GET
    @Operation(summary = "List recipients, newest first")
    public List<RecipientView> list(@QueryParam("limit") Integer limit) {
        return recipients.list(authenticated.id(), BalanceViews.limit(limit)).stream().map(RecipientView::of).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Retrieve a recipient")
    public RecipientView get(@PathParam("id") String id) {
        return RecipientView.of(recipients.findOwned(authenticated.id(), id));
    }

    @GET
    @Path("/{id}/balance")
    @Operation(summary = "A recipient's balance, available and pending, per currency")
    public BalanceViews.Balance balance(@PathParam("id") String id) {
        var r = recipients.findOwned(authenticated.id(), id);
        return new BalanceViews.Balance(r.id, ledger.balance(r.merchantId, r.id, Instant.now()));
    }

    @GET
    @Path("/{id}/transactions")
    @Operation(summary = "A recipient's statement, newest first")
    public List<BalanceViews.Transaction> transactions(@PathParam("id") String id,
                                                       @QueryParam("limit") Integer limit) {
        var r = recipients.findOwned(authenticated.id(), id);
        return BalanceViews.transactions(ledger.transactions(r.merchantId, r.id, BalanceViews.limit(limit)));
    }
}
