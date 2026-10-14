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

    @Test
    void paymentSucceededArrivesSignedWithTheMerchantSecret() {
        var m = Api.newMerchant(Api.SINK);
        String id = Api.newPayment(m);
        payWithPix(id);

        assertEquals(1, dispatcher.dispatchDue());
        assertEquals(1, WebhookSink.RECEIVED.size());

        var received = WebhookSink.RECEIVED.get(0);
        var event = JsonPath.from(received.body());
        assertEquals("payment.succeeded", event.getString("type"));
        assertTrue(event.getString("id").startsWith("evt_"));
        assertEquals(id, event.getString("data.id"));
        assertEquals("42", event.getString("data.metadata.tourId"));

        // O lojista recalcula a assinatura com o próprio segredo e compara.
        String t = received.signature().split(",")[0].substring(2);
        assertEquals(Signatures.header(m.webhookSecret(), Long.parseLong(t), received.body()), received.signature());
    }

    @Test
    void failedDeliveryIsRetriedLater() {
        var m = Api.newMerchant(Api.SINK + "?status=500");
        payWithPix(Api.newPayment(m));

        dispatcher.dispatchDue();

        Api.as(m).get("/v1/webhook-deliveries").then()
                .statusCode(200)
                .body("[0].status", equalTo("pending"))
                .body("[0].attempts", equalTo(1))
                .body("[0].lastStatusCode", equalTo(500));

        // Ainda não é hora da próxima tentativa: a rodada seguinte não a envia.
        assertEquals(0, dispatcher.dispatchDue());
    }

    @Test
    void merchantCanAskForADeliveryAgain() {
        var m = Api.newMerchant(Api.SINK + "?status=500");
        payWithPix(Api.newPayment(m));
        dispatcher.dispatchDue();

        String deliveryId = Api.as(m).get("/v1/webhook-deliveries").then().extract().path("[0].id");
        Api.as(m).post("/v1/webhook-deliveries/" + deliveryId + "/retry").then()
                .statusCode(200).body("attempts", equalTo(0));
        assertEquals(1, dispatcher.dispatchDue());
    }

    @Test
    void refundAndCancelAlsoNotify() {
        var m = Api.newMerchant(Api.SINK);
        String paid = Api.newPayment(m);
        payWithPix(paid);
        Api.as(m).body(Map.of()).post("/v1/payments/" + paid + "/refunds").then().statusCode(201);
        String canceled = Api.newPayment(m);
        Api.as(m).post("/v1/payments/" + canceled + "/cancel").then().statusCode(200);

        dispatcher.dispatchDue();
        var types = WebhookSink.RECEIVED.stream().map(r -> JsonPath.from(r.body()).getString("type")).sorted().toList();
        assertEquals(java.util.List.of("payment.canceled", "payment.succeeded", "refund.succeeded"), types);
    }

    @Test
    void noWebhookUrlMeansNothingIsQueued() {
        var m = Api.newMerchant();
        payWithPix(Api.newPayment(m));
        Api.as(m).get("/v1/webhook-deliveries").then().body("size()", equalTo(0));
    }

    @Test
    void givesUpAfterTheMaximumAttempts() {
        var m = Api.newMerchant(Api.SINK + "?status=503");
        payWithPix(Api.newPayment(m));

        for (int i = 0; i < 8; i++) {
            QuarkusTransaction.requiringNew().run(() -> WebhookDelivery.update(
                    "nextAttemptAt = ?1 where status = ?2", Instant.now(), WebhookDelivery.Status.PENDING));
            dispatcher.dispatchDue();
        }
        Api.as(m).get("/v1/webhook-deliveries").then()
                .body("[0].status", equalTo("failed"))
                .body("[0].attempts", equalTo(8));
    }
}
