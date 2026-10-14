package org.leonardo.standardpay.checkout.boundary;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.leonardo.standardpay.payments.control.PaymentProcessor;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;

import java.net.URI;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A página que o pagador vê — o equivalente à tela do PayPal.
 *
 * Sem chave de API: quem chega aqui é o turista, redirecionado pelo lojista.
 * O id da cobrança, impossível de adivinhar, é o que dá acesso.
 *
 * Em português porque fala com o pagador, ao contrário da API, que fala com
 * programadores.
 */
@Path("/checkout/{id}")
@Tag(name = "Checkout (payer-facing pages)")
@Produces(MediaType.TEXT_HTML)
@SecurityRequirementsSet // o pagador não tem chave: o que protege a página é o id
public class CheckoutResource {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Recusas como o pagador as lê. */
    private static final Map<String, String> DECLINE_MESSAGES = Map.of(
            "card_declined", "O cartão foi recusado pelo banco emissor. Tente outro cartão.",
            "insufficient_funds", "Saldo insuficiente. Tente outro cartão ou pague com PIX.",
            "expired_card", "Este cartão está vencido.",
            "incorrect_cvc", "O código de segurança (CVV) está incorreto.",
            "invalid_number", "O número do cartão é inválido. Confira os dígitos.");

    public record TestCard(String number, String result) {
    }

    /** O que a página mostra. Montado aqui para o template não ter lógica. */
    public record Page(String id, String merchantName, String description, String amountText,
                       String state, String error, String cardLast4, String method,
                       String pixCode, boolean hasReturn, List<TestCard> testCards) {
    }

    private static final List<TestCard> TEST_CARDS = List.of(
            new TestCard("4242 4242 4242 4242", "aprovado"),
            new TestCard("4000 0000 0000 0002", "recusado"),
            new TestCard("4000 0000 0000 9995", "saldo insuficiente"));

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance checkout(Page page);
    }

    @Inject
    PaymentProcessor processor;

    @GET
    @Operation(summary = "Payment page", description = "Where the payer is sent via checkoutUrl.")
    public TemplateInstance show(@PathParam("id") String id) {
        Payment p = processor.findForCheckout(id);
        if (p.status == PaymentStatus.PENDING) p = processor.expireIfOverdue(id);
        return render(p, null);
    }

    @POST
    @Path("/card")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response payWithCard(@PathParam("id") String id, @RestForm String cardNumber) {
        var attempt = processor.payWithCard(id, cardNumber);
        Payment p = attempt.payment();
        if (attempt.outcome() != null && !attempt.outcome().approved()) {
            String message = DECLINE_MESSAGES.getOrDefault(attempt.outcome().code(), "O pagamento foi recusado.");
            return Response.ok(render(p, message)).build();
        }
        return afterPayment(p);
    }

    @POST
    @Path("/pix")
    public Response payWithPix(@PathParam("id") String id) {
        return afterPayment(processor.payWithPix(id));
    }

    @POST
    @Path("/cancel")
    public Response cancel(@PathParam("id") String id) {
        Payment p = processor.cancelByPayer(id);
        String back = p.cancelUrl != null ? p.cancelUrl : p.returnUrl;
        if (p.status == PaymentStatus.CANCELED && back != null) {
            return Response.seeOther(backTo(back, p)).build();
        }
        return Response.ok(render(p, null)).build();
    }

    /** Aprovado com returnUrl: volta para o lojista, como no PayPal. Sem ela, mostra o recibo aqui. */
    private Response afterPayment(Payment p) {
        if (p.status == PaymentStatus.SUCCEEDED && p.returnUrl != null) {
            return Response.seeOther(backTo(p.returnUrl, p)).build();
        }
        return Response.ok(render(p, null)).build();
    }

    /**
     * A volta leva o id e o status na URL, por conveniência da tela do lojista.
     * Não é prova de pagamento — qualquer um digita uma URL. A prova é o webhook
     * assinado, ou a consulta à API.
     */
    private static URI backTo(String url, Payment p) {
        return UriBuilder.fromUri(url)
                .queryParam("payment_id", p.id)
                .queryParam("status", p.status.json())
                .build();
    }

    private TemplateInstance render(Payment p, String error) {
        boolean payable = p.isPayable(Instant.now());
        // Pelo id: depois da transação o pagamento está desligado da sessão, e
        // navegar até p.merchant.name daria LazyInitializationException. O id o
        // proxy do Hibernate entrega sem ir ao banco.
        Merchant merchant = Merchant.findById(p.merchant.id);
        String state = payable ? "pending" : p.status.json();
        return Templates.checkout(new Page(
                p.id, merchant.name, p.description, format(p.amount, p.currency), state, error,
                p.cardLast4, p.method == null ? null : p.method.name(),
                pixCode(p), p.returnUrl != null || p.cancelUrl != null, TEST_CARDS));
    }

    /** 19500 em BRL vira "R$ 195,00". */
    static String format(long cents, String currency) {
        var nf = NumberFormat.getCurrencyInstance(PT_BR);
        try {
            nf.setCurrency(Currency.getInstance(currency));
        } catch (IllegalArgumentException e) {
            return currency + " " + String.format(PT_BR, "%.2f", cents / 100.0);
        }
        return nf.format(cents / 100.0).replace(' ', ' ');
    }

    /**
     * Um "copia e cola" de mentira, com cara de PIX. Não é um BR Code válido de
     * propósito: nenhum banco deve conseguir pagá-lo.
     */
    private static String pixCode(Payment p) {
        return "00020126STANDARDPAY-SIMULACAO-" + p.id + "-" + p.amount + "5303986";
    }
}
