package org.leonardo.standardpay.refunds.boundary;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;
import org.leonardo.standardpay.refunds.control.Refunds;

import java.util.List;

@Path("/v1/payments/{paymentId}/refunds")
@Tag(name = "Refunds")
public class RefundsResource {

    @Inject
    Refunds refunds;

    @Inject
    AuthenticatedMerchant authenticated;

    public record CreateRefundRequest(
            @Min(value = 1, message = "must be at least 1 (amounts are in cents)")
            Long amount,
            @Size(max = 500, message = "must have at most 500 characters")
            String reason) {
    }

    @POST
    @Operation(summary = "Refund a payment",
            description = "Omit amount to refund everything still refundable. Partial refunds may be repeated.")
    public Response create(@PathParam("paymentId") String paymentId, @Valid CreateRefundRequest request) {
        Long amount = request == null ? null : request.amount();
        String reason = request == null ? null : request.reason();
        return Response.status(201)
                .entity(refunds.refund(authenticated.id(), paymentId, amount, reason))
                .build();
    }

    @GET
    @Operation(summary = "Refunds of a payment, oldest first")
    public List<Refunds.RefundView> list(@PathParam("paymentId") String paymentId) {
        return refunds.ofPayment(authenticated.id(), paymentId);
    }
}
