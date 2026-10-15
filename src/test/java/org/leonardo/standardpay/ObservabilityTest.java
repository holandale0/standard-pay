package org.leonardo.standardpay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;
import org.leonardo.standardpay.support.WebhookSink;
import org.leonardo.standardpay.webhooks.control.WebhookDispatcher;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Logs correlacionados e métricas de negócio, vistos de fora: pela API e pelo MeterRegistry. */
@QuarkusTest
class ObservabilityTest {

    @Inject
    MeterRegistry registry;

    @Inject
    WebhookDispatcher dispatcher;

    @BeforeEach
    void clear() {
        WebhookSink.RECEIVED.clear();
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    /** Uma chamada qualquer da API: o cadastro de lojista, que não exige chave. */
    private static Response signup(RequestSpecification request) {
        return request.contentType("application/json").body(Map.of("name", "Loja")).post("/v1/merchants");
    }

    private static void payWithPix(String paymentId) {
        given().redirects().follow(false).post("/checkout/" + paymentId + "/pix").then().statusCode(303);
    }

    // ── X-Request-Id ──

    @Test
    void everyResponseCarriesARequestId() {
        // Act
        var response = signup(given());

        // Assert
        response.then().header("X-Request-Id", matchesPattern("req_\\w{24}"));
    }

    @Test
    void aSafeRequestIdFromTheClientIsKept() {
        // Act
        var response = signup(given().header("X-Request-Id", "loja-pedido-1234"));

        // Assert
        response.then().header("X-Request-Id", equalTo("loja-pedido-1234"));
    }

    @Test
    void anUnsafeRequestIdIsReplaced() {
        // Arrange — espaços e sinais de marcação: forjariam conteúdo no log de quem o lesse
        String forged = "abc <script> fake log line";

        // Act
        var response = signup(given().header("X-Request-Id", forged));

        // Assert
        response.then().header("X-Request-Id", matchesPattern("req_\\w{24}"));
    }

    @Test
    void errorBodiesCarryTheRequestIdForSupport() {
        // Act
        var response = given().header("X-Request-Id", "suporte-42").get("/v1/payments");

        // Assert
        response.then()
                .statusCode(401)
                .body("error.requestId", equalTo("suporte-42"));
    }

    // ── Métricas de negócio ──

    @Test
    void createdPaymentsAreCounted() {
        // Arrange
        var m = Api.newMerchant();
        double before = count("standardpay.payments.created", "currency", "BRL");

        // Act
        Api.newPayment(m);

        // Assert
        assertEquals(before + 1, count("standardpay.payments.created", "currency", "BRL"));
    }

    @Test
    void succeededPaymentsAreCountedByMethodAndAmount() {
        // Arrange
        String id = Api.newPayment(Api.newMerchant());
        double before = count("standardpay.payments.succeeded", "method", "pix", "currency", "BRL");
        double amountBefore = count("standardpay.payments.succeeded.amount", "currency", "BRL");

        // Act
        payWithPix(id);

        // Assert
        assertEquals(before + 1, count("standardpay.payments.succeeded", "method", "pix", "currency", "BRL"));
        assertEquals(amountBefore + 19500, count("standardpay.payments.succeeded.amount", "currency", "BRL"));
    }

    @Test
    void declinesAreCountedByReason() {
        // Arrange
        String id = Api.newPayment(Api.newMerchant());
        double before = count("standardpay.payments.declined", "reason", "insufficient_funds");

        // Act
        given().formParam("cardNumber", "4000000000009995").post("/checkout/" + id + "/card");

        // Assert
        assertEquals(before + 1, count("standardpay.payments.declined", "reason", "insufficient_funds"));
    }

    @Test
    void rejectedRequestsAreNotCountedAsPayments() {
        // Arrange — valor inválido: a transação nem começa
        var m = Api.newMerchant();
        double before = count("standardpay.payments.created", "currency", "BRL");

        // Act
        Api.as(m).body(Map.of("amount", 0)).post("/v1/payments").then().statusCode(400);

        // Assert
        assertEquals(before, count("standardpay.payments.created", "currency", "BRL"));
    }

    @Test
    void refundsAreCountedByTypeAndAmount() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        payWithPix(id);
        double before = count("standardpay.refunds", "type", "partial");
        double amountBefore = count("standardpay.refunds.amount", "currency", "BRL");

        // Act
        Api.as(m).body(Map.of("amount", 5000)).post("/v1/payments/" + id + "/refunds").then().statusCode(201);

        // Assert
        assertEquals(before + 1, count("standardpay.refunds", "type", "partial"));
        assertEquals(amountBefore + 5000, count("standardpay.refunds.amount", "currency", "BRL"));
    }

    @Test
    void webhookDeliveriesAreTimedByOutcome() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        payWithPix(Api.newPayment(m));
        Timer timer = registry.find("standardpay.webhooks.delivery")
                .tags("event", "payment.succeeded", "outcome", "delivered").timer();
        long before = timer == null ? 0 : timer.count();

        // Act
        dispatcher.dispatchDue();

        // Assert
        Timer after = registry.find("standardpay.webhooks.delivery")
                .tags("event", "payment.succeeded", "outcome", "delivered").timer();
        assertEquals(before + 1, after.count());
    }

    @Test
    void latenciesArePublishedWithBucketsForPercentiles() {
        // Arrange
        signup(given()).then().statusCode(201);

        // Act
        Timer timer = registry.find("http.server.requests").tags("uri", "/v1/merchants").timer();

        // Assert — sem faixas, o p95 do painel daria NaN
        assertEquals(11, timer.takeSnapshot().histogramCounts().length);
    }

    @Test
    void webhookDeliveryCarriesTheTraceContext() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        payWithPix(Api.newPayment(m));

        // Act
        dispatcher.dispatchDue();

        // Assert — W3C traceparent: versão-traceId-spanId-flags
        assertEquals(1, WebhookSink.RECEIVED.size());
        assertTrue(
                WebhookSink.RECEIVED.get(0).traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),
                "traceparent: " + WebhookSink.RECEIVED.get(0).traceparent());
    }
}
