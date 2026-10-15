package org.leonardo.standardpay.webhooks.control;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.leonardo.standardpay.webhooks.control.WebhookTelemetry.Outcome;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Entrega os webhooks pendentes.
 *
 * Qualquer resposta 2xx conta como entregue. O resto — erro, timeout, servidor
 * fora do ar — agenda nova tentativa, com intervalos crescentes: o lojista que
 * ficou fora por uma hora recebe tudo quando voltar, sem ser martelado
 * enquanto estava caído.
 */
@ApplicationScoped
public class WebhookDispatcher {

    /** Espera antes da tentativa seguinte, pela quantidade de falhas até aqui. */
    static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));

    private static final int BATCH = 50;

    @ConfigProperty(name = "standardpay.webhooks.max-attempts")
    int maxAttempts;

    @ConfigProperty(name = "standardpay.webhooks.timeout")
    Duration timeout;

    @Inject
    WebhookTelemetry telemetry;

    @Inject
    Tracer tracer;

    @Inject
    OpenTelemetry openTelemetry;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Due(String id, String eventType, String url, String payload, String secret) {
    }

    /**
     * Uma rodada. O envio fica fora de transação: segurar uma conexão do banco
     * enquanto se espera um servidor lento de terceiros esgotaria o pool.
     *
     * @return quantos webhooks foram tentados nesta rodada
     */
    public int dispatchDue() {
        List<Due> due = QuarkusTransaction.requiringNew().call(() ->
                WebhookDelivery.due(Instant.now(), BATCH).stream()
                        .map(d -> new Due(d.id, d.eventType, d.targetUrl, d.payload, d.merchant.webhookSecret))
                        .toList());

        for (Due d : due) {
            long started = System.nanoTime();
            Result result = send(d);
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            Recorded recorded = QuarkusTransaction.requiringNew().call(() -> record(d.id(), result));
            // Depois do commit do registro: o log não afirma o que o banco não guardou.
            telemetry.attempt(d.id(), d.eventType(), recorded.attempts(), recorded.outcome(), took,
                    result.error(), recorded.nextAttemptAt());
        }

        telemetry.backlog(QuarkusTransaction.requiringNew().call(WebhookDelivery::countWaiting));
        return due.size();
    }

    /** O agendador não aceita retorno; a contagem fica para os testes. */
    @Scheduled(every = "${standardpay.webhooks.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledDispatch() {
        dispatchDue();
    }

    record Result(boolean delivered, Integer statusCode, String error) {
    }

    record Recorded(Outcome outcome, int attempts, Instant nextAttemptAt) {
    }

    /**
     * Cada envio é um trecho próprio do trace, e o cabeçalho {@code traceparent}
     * vai junto: se o lojista também usa OpenTelemetry, o trace dele continua o
     * nosso, e a investigação de "o webhook chegou?" atravessa os dois sistemas.
     */
    private Result send(Due d) {
        URI uri = URI.create(d.url());
        Span span = tracer.spanBuilder("POST webhook " + d.eventType())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "POST")
                // Host e caminho, mas não a query: a URL do lojista pode carregar um token ali.
                .setAttribute("server.address", String.valueOf(uri.getHost()))
                .setAttribute("url.path", String.valueOf(uri.getPath()))
                .setAttribute("standardpay.webhook.id", d.id())
                .setAttribute("standardpay.webhook.event", d.eventType())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            var builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "StandardPay-Webhooks/1.0")
                    .header(Signatures.HEADER,
                            Signatures.header(d.secret(), Instant.now().getEpochSecond(), d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()));
            openTelemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), builder, (carrier, key, value) -> carrier.header(key, value));

            HttpResponse<Void> response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            boolean ok = code >= 200 && code < 300;
            span.setAttribute("http.response.status_code", code);
            if (!ok) span.setStatus(StatusCode.ERROR, "HTTP " + code);
            return new Result(ok, code, ok ? null : "HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.setStatus(StatusCode.ERROR, "Interrupted");
            return new Result(false, null, "Interrupted");
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, truncate(message));
            return new Result(false, null, truncate(message));
        } finally {
            span.end();
        }
    }

    private Recorded record(String id, Result result) {
        WebhookDelivery d = WebhookDelivery.findById(id);
        d.attempts++;
        d.lastStatusCode = result.statusCode();
        d.lastError = result.error();

        if (result.delivered()) {
            d.status = WebhookDelivery.Status.DELIVERED;
            d.deliveredAt = Instant.now();
            return new Recorded(Outcome.DELIVERED, d.attempts, null);
        }
        if (d.attempts >= maxAttempts) {
            d.status = WebhookDelivery.Status.FAILED;
            return new Recorded(Outcome.GAVE_UP, d.attempts, null);
        }
        Duration wait = BACKOFF.get(Math.min(d.attempts - 1, BACKOFF.size() - 1));
        d.nextAttemptAt = Instant.now().plus(wait);
        return new Recorded(Outcome.RETRY, d.attempts, d.nextAttemptAt);
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
