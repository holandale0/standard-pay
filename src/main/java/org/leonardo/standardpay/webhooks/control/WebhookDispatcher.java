package org.leonardo.standardpay.webhooks.control;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Due(String id, String url, String payload, String secret) {
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
                        .map(d -> new Due(d.id, d.targetUrl, d.payload, d.merchant.webhookSecret))
                        .toList());

        for (Due d : due) {
            Result result = send(d);
            QuarkusTransaction.requiringNew().run(() -> record(d.id(), result));
        }
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

    private Result send(Due d) {
        try {
            var request = HttpRequest.newBuilder(URI.create(d.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "StandardPay-Webhooks/1.0")
                    .header(Signatures.HEADER,
                            Signatures.header(d.secret(), Instant.now().getEpochSecond(), d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            boolean ok = code >= 200 && code < 300;
            return new Result(ok, code, ok ? null : "HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "Interrupted");
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Result(false, null, truncate(message));
        }
    }

    private void record(String id, Result result) {
        WebhookDelivery d = WebhookDelivery.findById(id);
        d.attempts++;
        d.lastStatusCode = result.statusCode();
        d.lastError = result.error();

        if (result.delivered()) {
            d.status = WebhookDelivery.Status.DELIVERED;
            d.deliveredAt = Instant.now();
        } else if (d.attempts >= maxAttempts) {
            d.status = WebhookDelivery.Status.FAILED;
            Log.warnf("Webhook %s gave up after %d attempts: %s", d.id, d.attempts, d.lastError);
        } else {
            Duration wait = BACKOFF.get(Math.min(d.attempts - 1, BACKOFF.size() - 1));
            d.nextAttemptAt = Instant.now().plus(wait);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
