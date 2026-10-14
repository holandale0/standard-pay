package org.leonardo.standardpay.webhooks;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;
import org.leonardo.standardpay.support.WebhookSink;
import org.leonardo.standardpay.webhooks.control.Signatures;
import org.leonardo.standardpay.webhooks.control.WebhookDispatcher;
import org.leonardo.standardpay.webhooks.entity.WebhookDelivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WebhooksTest {

    @Inject
    WebhookDispatcher dispatcher;

    @BeforeEach
    void clear() {
        WebhookSink.RECEIVED.clear();
        // Entregas que outros testes deixaram pendentes não podem cair aqui.
        QuarkusTransaction.requiringNew().run(() -> WebhookDelivery.update("status = ?1 where status = ?2",
                WebhookDelivery.Status.FAILED, WebhookDelivery.Status.PENDING));
    }

    private static void payWithPix(String id) {
        given().redirects().follow(false).post("/checkout/" + id + "/pix").then().statusCode(303);
    }

    /** Traz para agora a próxima tentativa de tudo o que está pendente. */
    private static void makePendingDue() {
        QuarkusTransaction.requiringNew().run(() -> WebhookDelivery.update(
                "nextAttemptAt = ?1 where status = ?2", Instant.now(), WebhookDelivery.Status.PENDING));
    }

    @Test
    void paymentSucceededArrivesWithTheEventAndThePayment() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        String id = Api.newPayment(m);
        payWithPix(id);

        // Act
        int sent = dispatcher.dispatchDue();

        // Assert
        assertEquals(1, sent);

        // Verify
        assertEquals(1, WebhookSink.RECEIVED.size());
        var event = JsonPath.from(WebhookSink.RECEIVED.get(0).body());
        assertEquals("payment.succeeded", event.getString("type"));
        assertTrue(event.getString("id").startsWith("evt_"));
        assertEquals(id, event.getString("data.id"));
        assertEquals("42", event.getString("data.metadata.tourId"));
    }

    @Test
    void signatureMatchesWhatTheMerchantComputesWithItsSecret() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        payWithPix(Api.newPayment(m));
        dispatcher.dispatchDue();
        var received = WebhookSink.RECEIVED.get(0);
        long timestamp = Long.parseLong(received.signature().split(",")[0].substring(2));

        // Act — o que o lojista faz do lado dele
        String expected = Signatures.header(m.webhookSecret(), timestamp, received.body());

        // Assert
        assertEquals(expected, received.signature());
    }

    @Test
    void failedDeliveryRecordsTheAttemptAndSchedulesAnother() {
        // Arrange
        var m = Api.newMerchant(Api.SINK + "?status=500");
        payWithPix(Api.newPayment(m));

        // Act
        dispatcher.dispatchDue();

        // Assert
        Api.as(m).get("/v1/webhook-deliveries").then()
                .statusCode(200)
                .body("[0].status", equalTo("pending"))
                .body("[0].attempts", equalTo(1))
                .body("[0].lastStatusCode", equalTo(500))
                .body("[0].nextAttemptAt", notNullValue());
    }

    @Test
    void failedDeliveryIsNotSentAgainBeforeItsTime() {
        // Arrange
        var m = Api.newMerchant(Api.SINK + "?status=500");
        payWithPix(Api.newPayment(m));
        dispatcher.dispatchDue();

        // Act
        int sent = dispatcher.dispatchDue();

        // Assert
        assertEquals(0, sent);
    }

    @Test
    void merchantCanAskForADeliveryAgain() {
        // Arrange
        var m = Api.newMerchant(Api.SINK + "?status=500");
        payWithPix(Api.newPayment(m));
        dispatcher.dispatchDue();
        String deliveryId = Api.as(m).get("/v1/webhook-deliveries").then().extract().path("[0].id");

        // Act
        var response = Api.as(m).post("/v1/webhook-deliveries/" + deliveryId + "/retry");

        // Assert
        response.then().statusCode(200).body("attempts", equalTo(0));

        // Verify — sai já na próxima rodada, sem esperar o intervalo
        assertEquals(1, dispatcher.dispatchDue());
    }

    @Test
    void merchantCannotRetryAnotherMerchantsDelivery() {
        // Arrange
        var owner = Api.newMerchant(Api.SINK + "?status=500");
        var other = Api.newMerchant();
        payWithPix(Api.newPayment(owner));
        dispatcher.dispatchDue();
        String deliveryId = Api.as(owner).get("/v1/webhook-deliveries").then().extract().path("[0].id");

        // Act
        var response = Api.as(other).post("/v1/webhook-deliveries/" + deliveryId + "/retry");

        // Assert
        response.then().statusCode(404);
    }

    @Test
    void refundAndCancelAlsoNotify() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        String paid = Api.newPayment(m);
        payWithPix(paid);
        Api.as(m).body(Map.of()).post("/v1/payments/" + paid + "/refunds").then().statusCode(201);
        String canceled = Api.newPayment(m);
        Api.as(m).post("/v1/payments/" + canceled + "/cancel").then().statusCode(200);

        // Act
        dispatcher.dispatchDue();

        // Assert
        var types = WebhookSink.RECEIVED.stream().map(r -> JsonPath.from(r.body()).getString("type")).sorted().toList();
        assertEquals(List.of("payment.canceled", "payment.succeeded", "refund.succeeded"), types);
    }

    @Test
    void noWebhookUrlMeansNothingIsQueued() {
        // Arrange
        var m = Api.newMerchant();

        // Act
        payWithPix(Api.newPayment(m));

        // Assert
        Api.as(m).get("/v1/webhook-deliveries").then().body("size()", equalTo(0));
    }

    @Test
    void givesUpAfterTheMaximumAttempts() {
        // Arrange — sete falhas, com a próxima tentativa sempre já vencida
        var m = Api.newMerchant(Api.SINK + "?status=503");
        payWithPix(Api.newPayment(m));
        for (int i = 0; i < 7; i++) {
            makePendingDue();
            dispatcher.dispatchDue();
        }
        makePendingDue();

        // Act — a oitava
        dispatcher.dispatchDue();

        // Assert
        Api.as(m).get("/v1/webhook-deliveries").then()
                .body("[0].status", equalTo("failed"))
                .body("[0].attempts", equalTo(8))
                .body("[0].nextAttemptAt", nullValue());

        // Verify — desistiu: não sai mais em rodada nenhuma
        makePendingDue();
        assertEquals(0, dispatcher.dispatchDue());
    }
}
