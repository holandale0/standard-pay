package org.leonardo.standardpay.payments.boundary;

import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;
import org.leonardo.standardpay.payments.control.PaymentProcessor;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;
import org.leonardo.standardpay.payments.entity.PaymentView;
import org.leonardo.standardpay.platform.ApiException;

import java.util.List;
import java.util.Map;

@Path("/v1/payments")
@Tag(name = "Payments")
public class PaymentsResource {

    private static final int MAX_METADATA_KEYS = 20;
    private static final int MAX_METADATA_VALUE = 500;

    @Inject
    PaymentProcessor processor;

    @Inject
    AuthenticatedMerchant authenticated;

    public record CreatePaymentRequest(
            @NotNull(message = "is required")
            @Min(value = 1, message = "must be at least 1 (amounts are in cents)")
            @Max(value = 99_999_999, message = "must be at most 99999999 (amounts are in cents)")
            Long amount,
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO 4217 code, such as BRL")
            String currency,
            @Size(max = 500, message = "must have at most 500 characters")
            String description,
            Map<String, String> metadata,
            @Size(max = 1000, message = "must have at most 1000 characters")
            String returnUrl,
            @Size(max = 1000, message = "must have at most 1000 characters")
            String cancelUrl,
            List<@Valid @NotNull SplitRequest> splits) {
    }

    /** A parte de um recebedor, em centavos. */
    public record SplitRequest(
            @NotBlank(message = "is required")
            String recipientId,
            @NotNull(message = "is required")
            @Min(value = 1, message = "must be at least 1 (amounts are in cents)")
            Long amount) {
    }

    @POST
    @Operation(summary = "Create a payment",
            description = "Returns a checkoutUrl. Redirect the payer there; you are notified by webhook when they pay. "
                    + "Send an Idempotency-Key header to make retries safe.")
    public Response create(@Valid @NotNull CreatePaymentRequest request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        validateMetadata(request.metadata());
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 200) {
            throw ApiException.invalid("invalid_idempotency_key", "Idempotency-Key must have at most 200 characters.");
        }

        var command = new PaymentProcessor.NewPayment(request.amount(), request.currency(), request.description(),
                request.metadata(), request.returnUrl(), request.cancelUrl(),
                request.splits() == null ? List.of() : request.splits().stream()
                        .map(s -> new PaymentProcessor.Split(s.recipientId(), s.amount())).toList());
        Payment payment;
        try {
            payment = processor.create(authenticated.get(), command, key);
        } catch (PersistenceException e) {
            // Duas requisições com a mesma chave ao mesmo tempo: as duas passam pela
            // checagem, e a restrição única do banco barra a segunda. Ela recebe a
            // cobrança que a primeira criou — o resultado que a idempotência promete.
            if (key == null) throw e;
            payment = Payment.findByIdempotencyKey(authenticated.id(), key).orElseThrow(() -> e);
        }
        return Response.status(201).entity(processor.view(payment)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Retrieve a payment")
    public PaymentView get(@PathParam("id") String id) {
        return processor.view(processor.findOwned(authenticated.id(), id));
    }

    @GET
    @Operation(summary = "List payments, newest first")
    public List<PaymentView> list(@QueryParam("status") String status, @QueryParam("limit") Integer limit) {
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return processor.list(authenticated.id(), parseStatus(status), n).stream().map(processor::view).toList();
    }

    @POST
    @Path("/{id}/cancel")
    @Operation(summary = "Cancel a pending payment", description = "The payer can no longer pay it.")
    public PaymentView cancel(@PathParam("id") String id) {
        return processor.view(processor.cancelByMerchant(authenticated.id(), id));
    }

    private static PaymentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return PaymentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("invalid_status",
                    "status must be one of: pending, succeeded, canceled, expired.");
        }
    }

    private static void validateMetadata(Map<String, String> metadata) {
        if (metadata == null) return;
        if (metadata.size() > MAX_METADATA_KEYS) {
            throw ApiException.invalid("invalid_metadata", "metadata can have at most " + MAX_METADATA_KEYS + " keys.");
        }
        for (var e : metadata.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 40) {
                throw ApiException.invalid("invalid_metadata", "metadata keys must have 1 to 40 characters.");
            }
            if (e.getValue() != null && e.getValue().length() > MAX_METADATA_VALUE) {
                throw ApiException.invalid("invalid_metadata",
                        "metadata values must have at most " + MAX_METADATA_VALUE + " characters.");
            }
        }
    }
}
