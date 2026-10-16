package org.leonardo.standardpay.dashboard.boundary;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestCookie;
import org.jboss.resteasy.reactive.RestForm;
import org.leonardo.standardpay.balances.control.Ledger;
import org.leonardo.standardpay.balances.control.Recipients;
import org.leonardo.standardpay.balances.entity.LedgerEntry;
import org.leonardo.standardpay.balances.entity.Recipient;
import org.leonardo.standardpay.merchants.boundary.AuthenticatedMerchant;
import org.leonardo.standardpay.merchants.boundary.PanelCsrf;
import org.leonardo.standardpay.merchants.boundary.PanelSessionFilter;
import org.leonardo.standardpay.merchants.control.PanelSessions;
import org.leonardo.standardpay.merchants.entity.Merchant;
import org.leonardo.standardpay.payments.control.PaymentProcessor;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.payments.entity.PaymentStatus;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Money;
import org.leonardo.standardpay.refunds.control.Refunds;
import org.leonardo.standardpay.webhooks.control.WebhookDeliveries;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * O painel web do lojista: o que a API faz, numa tela.
 *
 * Em português, como a página de checkout: quem usa é gente, não programa. As
 * páginas recebem tudo já formatado — valores em reais, datas no fuso
 * configurado, estados por extenso — e o template só arruma na tela.
 *
 * Cada ação volta para a página de onde veio com um código de aviso ou de erro
 * na URL, e a mensagem sai de um mapa daqui: um link forjado não consegue pôr
 * texto arbitrário na tela do lojista.
 */
@Path("/painel")
@Produces(MediaType.TEXT_HTML)
public class PanelResource {

    // ── Modelos das páginas ──

    /** O que toda página tem: o menu, o lojista, o token dos formulários e o aviso da última ação. */
    public record Shell(String merchantName, String csrf, String section, String notice, String error) {
    }

    public record BalanceRow(String currency, String available, String pending, boolean negative) {
    }

    public record PaymentRow(String id, String createdAt, String description, String amount,
                             String status, String statusClass) {
    }

    public record PaymentDetail(String id, String amount, String status, String statusClass, String description,
                                String method, String createdAt, String paidAt, String refunded,
                                String refundable, boolean canRefund, boolean canCancel, String checkoutUrl,
                                List<Map.Entry<String, String>> metadata) {
    }

    public record SplitRow(String recipientId, String recipientName, String amount) {
    }

    public record RefundRow(String id, String amount, String status, String statusClass, String reason,
                            String createdAt, String settledAt, String failure) {
    }

    public record RecipientRow(String id, String name, String createdAt, List<BalanceRow> balances) {
    }

    public record TransactionRow(String createdAt, String type, String amount, boolean credit, String status,
                                 String paymentId, String availableAt) {
    }

    public record DeliveryRow(String id, String eventType, String createdAt, String status, String statusClass,
                              int attempts, String lastResult) {
    }

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance entrar(String error);

        static native TemplateInstance inicio(Shell shell, List<BalanceRow> balances, List<PaymentRow> payments,
                                              String apiKeyLast4, String webhookUrl);

        static native TemplateInstance cobrancas(Shell shell, String filter, List<PaymentRow> payments);

        static native TemplateInstance cobranca(Shell shell, PaymentDetail payment, List<SplitRow> splits,
                                                List<RefundRow> refunds);

        static native TemplateInstance recebedores(Shell shell, List<RecipientRow> recipients);

        static native TemplateInstance recebedor(Shell shell, RecipientRow recipient,
                                                 List<TransactionRow> transactions);

        static native TemplateInstance extrato(Shell shell, List<BalanceRow> balances,
                                               List<TransactionRow> transactions);

        static native TemplateInstance webhooks(Shell shell, String webhookUrl, List<DeliveryRow> deliveries);
    }

    // ── Avisos e erros, pelo código que vem na URL ──

    private static final Map<String, String> NOTICES = Map.of(
            "estorno", "Estorno pedido.",
            "cancelada", "Cobrança cancelada.",
            "recebedor", "Recebedor cadastrado.",
            "reenvio", "Webhook de volta na fila; sai na próxima rodada.");

    private static final Map<String, String> ERRORS = Map.ofEntries(
            Map.entry("amount_too_large", "O valor passa do que ainda pode ser estornado."),
            Map.entry("already_refunded", "Esta cobrança já foi estornada por inteiro."),
            Map.entry("payment_not_refundable", "Só cobranças pagas podem ser estornadas."),
            Map.entry("payment_not_cancelable", "Só cobranças pendentes podem ser canceladas."),
            Map.entry("invalid_amount", "Valor inválido. Use reais, como 50 ou 50,00."),
            Map.entry("invalid_name", "Dê um nome ao recebedor, com até 120 caracteres."));

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Inject
    AuthenticatedMerchant authenticated;

    @Inject
    PanelCsrf csrf;

    @Inject
    PanelSessions sessions;

    @Inject
    PaymentProcessor payments;

    @Inject
    Refunds refunds;

    @Inject
    Recipients recipients;

    @Inject
    Ledger ledger;

    @Inject
    WebhookDeliveries deliveries;

    @ConfigProperty(name = "standardpay.panel.time-zone")
    ZoneId zone;

    @ConfigProperty(name = "standardpay.public-url")
    String publicUrl;

    // ── Entrar e sair ──

    @GET
    @Path("/entrar")
    public TemplateInstance loginPage() {
        return Templates.entrar(null);
    }

    @POST
    @Path("/entrar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response login(@RestForm String apiKey) {
        return sessions.open(apiKey == null ? null : apiKey.trim())
                .map(opened -> Response.seeOther(URI.create("/painel"))
                        .cookie(sessionCookie(opened.token(), (int) sessions.lifetime().toSeconds()))
                        .build())
                .orElseGet(() -> Response.status(401)
                        .entity(Templates.entrar("Chave de API inválida. Ela começa com sk_test_."))
                        .build());
    }

    @POST
    @Path("/sair")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logout(@RestForm String csrf, @RestCookie(PanelSessionFilter.COOKIE) String token) {
        this.csrf.check(csrf);
        sessions.close(token);
        return Response.seeOther(URI.create(PanelSessionFilter.LOGIN)).cookie(sessionCookie("", 0)).build();
    }

    // ── Páginas ──

    @GET
    public TemplateInstance home(@QueryParam("ok") String ok, @QueryParam("erro") String erro) {
        Merchant m = authenticated.get();
        return Templates.inicio(shell("inicio", ok, erro), balances(m.id, null),
                paymentRows(payments.list(m.id, null, 10)), m.apiKeyLast4, m.webhookUrl);
    }

    @GET
    @Path("/cobrancas")
    public TemplateInstance paymentList(@QueryParam("status") String status) {
        PaymentStatus filter = parseStatus(status);
        return Templates.cobrancas(shell("cobrancas", null, null), filter == null ? "" : filter.json(),
                paymentRows(payments.list(authenticated.id(), filter, 100)));
    }

    @GET
    @Path("/cobrancas/{id}")
    public TemplateInstance payment(@PathParam("id") String id, @QueryParam("ok") String ok,
                                    @QueryParam("erro") String erro) {
        Payment p = payments.findOwned(authenticated.id(), id);
        var splits = p.splits.stream()
                .map(s -> new SplitRow(s.recipientId, Recipient.findOwned(authenticated.id(), s.recipientId)
                        .map(r -> r.name).orElse(s.recipientId), Money.format(s.amount, p.currency)))
                .toList();
        var refundRows = refunds.ofPayment(authenticated.id(), id).stream()
                .map(r -> new RefundRow(r.id(), Money.format(r.amount(), p.currency), refundLabel(r.status()),
                        r.status(), r.reason(), date(r.createdAt()), date(r.settledAt()), r.failureMessage()))
                .toList();
        return Templates.cobranca(shell("cobrancas", ok, erro), detail(p), splits, refundRows);
    }

    @POST
    @Path("/cobrancas/{id}/estornar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response refund(@PathParam("id") String id, @RestForm String csrf, @RestForm String valor,
                           @RestForm String motivo) {
        this.csrf.check(csrf);
        String back = "/painel/cobrancas/" + id;
        Long amount = null;
        if (valor != null && !valor.isBlank()) {
            var parsed = Money.parse(valor);
            if (parsed.isEmpty()) return back(back, "erro", "invalid_amount");
            amount = parsed.get();
        }
        String reason = motivo == null || motivo.isBlank() ? null : motivo.trim();
        if (reason != null && reason.length() > 500) reason = reason.substring(0, 500);
        try {
            refunds.refund(authenticated.id(), id, amount, reason);
            return back(back, "ok", "estorno");
        } catch (ApiException e) {
            return back(back, "erro", e.code());
        }
    }

    @POST
    @Path("/cobrancas/{id}/cancelar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response cancel(@PathParam("id") String id, @RestForm String csrf) {
        this.csrf.check(csrf);
        String back = "/painel/cobrancas/" + id;
        try {
            payments.cancelByMerchant(authenticated.id(), id);
            return back(back, "ok", "cancelada");
        } catch (ApiException e) {
            return back(back, "erro", e.code());
        }
    }

    @GET
    @Path("/recebedores")
    public TemplateInstance recipientList(@QueryParam("ok") String ok, @QueryParam("erro") String erro) {
        var rows = recipients.list(authenticated.id(), 100).stream().map(this::recipientRow).toList();
        return Templates.recebedores(shell("recebedores", ok, erro), rows);
    }

    @POST
    @Path("/recebedores")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createRecipient(@RestForm String csrf, @RestForm String nome) {
        this.csrf.check(csrf);
        if (nome == null || nome.isBlank() || nome.trim().length() > 120) {
            return back("/painel/recebedores", "erro", "invalid_name");
        }
        recipients.create(authenticated.id(), nome, null);
        return back("/painel/recebedores", "ok", "recebedor");
    }

    @GET
    @Path("/recebedores/{id}")
    public TemplateInstance recipient(@PathParam("id") String id) {
        Recipient r = recipients.findOwned(authenticated.id(), id);
        return Templates.recebedor(shell("recebedores", null, null), recipientRow(r),
                transactionRows(ledger.transactions(r.merchantId, r.id, 100)));
    }

    @GET
    @Path("/extrato")
    public TemplateInstance statement() {
        String merchantId = authenticated.id();
        return Templates.extrato(shell("extrato", null, null), balances(merchantId, null),
                transactionRows(ledger.transactions(merchantId, null, 100)));
    }

    @GET
    @Path("/webhooks")
    public TemplateInstance webhookList(@QueryParam("ok") String ok, @QueryParam("erro") String erro) {
        Merchant m = authenticated.get();
        var rows = deliveries.recent(m.id, 50).stream().map(this::deliveryRow).toList();
        return Templates.webhooks(shell("webhooks", ok, erro), m.webhookUrl, rows);
    }

    @POST
    @Path("/webhooks/{id}/reenviar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response retryWebhook(@PathParam("id") String id, @RestForm String csrf) {
        this.csrf.check(csrf);
        deliveries.retry(authenticated.id(), id);
        return back("/painel/webhooks", "ok", "reenvio");
    }

    // ── Montagem ──

    private Shell shell(String section, String ok, String erro) {
        String notice = ok == null ? null : NOTICES.get(ok);
        String error = erro == null ? null : ERRORS.getOrDefault(erro, "Não foi possível concluir a operação.");
        return new Shell(authenticated.get().name, csrf.token(), section, notice, error);
    }

    private List<BalanceRow> balances(String merchantId, String recipientId) {
        return ledger.balance(merchantId, recipientId, Instant.now()).stream()
                .map(t -> new BalanceRow(t.currency(), Money.format(t.available(), t.currency()),
                        Money.format(t.pending(), t.currency()), t.available() < 0))
                .toList();
    }

    private List<PaymentRow> paymentRows(List<Payment> list) {
        return list.stream()
                .map(p -> new PaymentRow(p.id, date(p.createdAt), p.description, Money.format(p.amount, p.currency),
                        paymentLabel(p), p.status.json()))
                .toList();
    }

    private PaymentDetail detail(Payment p) {
        String method = p.method == null ? null
                : p.method == Payment.Method.PIX ? "PIX" : "Cartão final " + p.cardLast4;
        return new PaymentDetail(p.id, Money.format(p.amount, p.currency), paymentLabel(p), p.status.json(),
                p.description, method, date(p.createdAt), date(p.paidAt),
                Money.format(p.amountRefunded, p.currency), Money.format(p.refundable(), p.currency),
                p.refundable() > 0, p.status == PaymentStatus.PENDING,
                publicUrl + "/checkout/" + p.id, List.copyOf(p.metadata.entrySet()));
    }

    private RecipientRow recipientRow(Recipient r) {
        return new RecipientRow(r.id, r.name, date(r.createdAt), balances(r.merchantId, r.id));
    }

    private List<TransactionRow> transactionRows(List<LedgerEntry> entries) {
        var now = Instant.now();
        return entries.stream()
                .map(e -> new TransactionRow(date(e.createdAt), ledgerLabel(e.type),
                        Money.format(e.amount, e.currency), e.amount > 0,
                        e.availableAt.isAfter(now) ? "pendente" : "disponível", e.paymentId, date(e.availableAt)))
                .toList();
    }

    private DeliveryRow deliveryRow(WebhookDelivery d) {
        String last = d.lastStatusCode != null ? "HTTP " + d.lastStatusCode
                : d.lastError != null ? d.lastError : "—";
        String status = switch (d.status) {
            case PENDING -> d.attempts == 0 ? "na fila" : "reenviando";
            case DELIVERED -> "entregue";
            case FAILED -> "desistiu";
        };
        return new DeliveryRow(d.id, d.eventType, date(d.createdAt), status, d.status.name().toLowerCase(),
                d.attempts, last);
    }

    private static String paymentLabel(Payment p) {
        if (p.status == PaymentStatus.SUCCEEDED && p.amountRefunded > 0) {
            return p.refundable() == 0 ? "estornada" : "estornada em parte";
        }
        return switch (p.status) {
            case PENDING -> "pendente";
            case SUCCEEDED -> "paga";
            case CANCELED -> "cancelada";
            case EXPIRED -> "expirada";
        };
    }

    private static String refundLabel(String status) {
        return switch (status) {
            case "pending" -> "pendente";
            case "succeeded" -> "concluído";
            case "failed" -> "falhou";
            default -> status;
        };
    }

    private static String ledgerLabel(LedgerEntry.Type type) {
        return switch (type) {
            case PAYMENT -> "Recebimento";
            case REFUND -> "Estorno";
            case REFUND_FAILURE -> "Estorno que falhou (devolvido)";
        };
    }

    private String date(Instant instant) {
        return instant == null ? null : DATE_TIME.format(instant.atZone(zone));
    }

    private static PaymentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return PaymentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Response back(String path, String key, String code) {
        return Response.seeOther(UriBuilder.fromPath(path).queryParam(key, code).build()).build();
    }

    /**
     * HttpOnly: nenhum script lê o cookie. SameSite=Lax: outro site não consegue
     * postar com ele. Secure quando o StandardPay está publicado em https.
     */
    private NewCookie sessionCookie(String value, int maxAge) {
        return new NewCookie.Builder(PanelSessionFilter.COOKIE)
                .value(value)
                .path("/painel")
                .httpOnly(true)
                .secure(publicUrl.startsWith("https://"))
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }
}
