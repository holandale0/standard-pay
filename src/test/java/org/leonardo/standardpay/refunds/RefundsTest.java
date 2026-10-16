package org.leonardo.standardpay.refunds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.refunds.control.Refunds;
import org.leonardo.standardpay.support.Api;
import org.leonardo.standardpay.support.WebhookSink;
import org.leonardo.standardpay.webhooks.control.WebhookDispatcher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RefundsTest {

    /** Estorno concluído só depois de 3 dias (o padrão de standardpay.refunds.delayed-by). */
    private static final String DELAYED_REFUND_CARD = "4000000000007726";

    /** Estorno que falha depois de 1 minuto (standardpay.refunds.fails-after). */
    private static final String FAILING_REFUND_CARD = "4000000000005126";

    @Inject
    Refunds refunds;

    @Inject
    WebhookDispatcher dispatcher;

    /** Cobrança de R$ 195,00 já paga com PIX. */
    private static String paidPayment(Api.Merchant m) {
        String id = Api.newPayment(m);
        given().redirects().follow(false).post("/checkout/" + id + "/pix").then().statusCode(303);
        return id;
    }

    /** Cobrança de R$ 195,00 já paga com o cartão dado. */
    private static String paidWithCard(Api.Merchant m, String cardNumber) {
        String id = Api.newPayment(m);
        given().redirects().follow(false).formParam("cardNumber", cardNumber).post("/checkout/" + id + "/card");
        Api.as(m).get("/v1/payments/" + id).then().body("status", equalTo("succeeded"));
        return id;
    }

    private static String refund(Api.Merchant m, String paymentId, Map<String, ?> body) {
        return Api.as(m).body(body).post("/v1/payments/" + paymentId + "/refunds")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void partialRefundReturnsPartOfTheAmount() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);

        // Act
        var response = Api.as(m).body(Map.of("amount", 5000, "reason", "One person fewer"))
                .post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(201)
                .body("id", startsWith("re_"))
                .body("amount", equalTo(5000))
                .body("reason", equalTo("One person fewer"))
                .body("payment.amountRefunded", equalTo(5000));

        // Verify
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("amountRefunded", equalTo(5000));
    }

    @Test
    void withoutAmountRefundsEverythingThatIsLeft() {
        // Arrange — R$ 50,00 já estornados de R$ 195,00
        var m = Api.newMerchant();
        String id = paidPayment(m);
        refund(m, id, Map.of("amount", 5000));

        // Act
        var response = Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(201)
                .body("amount", equalTo(14500))
                .body("payment.amountRefunded", equalTo(19500));

        // Verify
        Api.as(m).get("/v1/payments/" + id + "/refunds").then()
                .body("size()", equalTo(2))
                .body("amount", contains(5000, 14500));
    }

    @Test
    void fullyRefundedPaymentCannotBeRefundedAgain() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);
        refund(m, id, Map.of());

        // Act
        var response = Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(409).body("error.code", equalTo("already_refunded"));
    }

    @Test
    void cannotRefundMoreThanWasPaid() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);

        // Act
        var response = Api.as(m).body(Map.of("amount", 20000)).post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(400).body("error.code", equalTo("amount_too_large"));

        // Verify — nada foi estornado
        Api.as(m).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(0));
    }

    @Test
    void cannotRefundAPendingPayment() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);

        // Act
        var response = Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(409).body("error.code", equalTo("payment_not_refundable"));
    }

    @Test
    void cannotRefundAnotherMerchantsPayment() {
        // Arrange
        var owner = Api.newMerchant();
        var other = Api.newMerchant();
        String id = paidPayment(owner);

        // Act
        var response = Api.as(other).body(Map.of()).post("/v1/payments/" + id + "/refunds");

        // Assert
        response.then().statusCode(404);

        // Verify
        Api.as(owner).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(0));
    }

    // ── Estornos que levam tempo, e que falham ──

    @Test
    void refundOfADelayedCardStaysPendingAndAlreadyCounts() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidWithCard(m, DELAYED_REFUND_CARD);

        // Act
        var response = Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds");

        // Assert — o pendente já conta: não dá para estornar de novo enquanto isso
        response.then().statusCode(201)
                .body("status", equalTo("pending"))
                .body("settledAt", nullValue())
                .body("payment.amountRefunded", equalTo(19500));
    }

    @Test
    void pendingRefundDoesNotSettleBeforeItsTime() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidWithCard(m, DELAYED_REFUND_CARD);
        refund(m, id, Map.of());

        // Act — dois dias depois, de três
        refunds.settleDue(Instant.now().plus(Duration.ofDays(2)));

        // Assert
        Api.as(m).get("/v1/payments/" + id + "/refunds").then().body("[0].status", equalTo("pending"));
    }

    @Test
    void pendingRefundSucceedsAfterItsTime() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidWithCard(m, DELAYED_REFUND_CARD);
        refund(m, id, Map.of("amount", 5000));

        // Act
        refunds.settleDue(Instant.now().plus(Duration.ofDays(4)));

        // Assert
        Api.as(m).get("/v1/payments/" + id + "/refunds").then()
                .body("[0].status", equalTo("succeeded"))
                .body("[0].settledAt", notNullValue());

        // Verify
        Api.as(m).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(5000));
    }

    @Test
    void failedRefundGivesTheAmountBack() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidWithCard(m, FAILING_REFUND_CARD);
        refund(m, id, Map.of());

        // Act
        refunds.settleDue(Instant.now().plus(Duration.ofMinutes(2)));

        // Assert
        Api.as(m).get("/v1/payments/" + id + "/refunds").then()
                .body("[0].status", equalTo("failed"))
                .body("[0].failureCode", equalTo("expired_or_canceled_card"))
                .body("[0].failureMessage", not(emptyOrNullString()));

        // Verify — o valor volta a ser estornável
        Api.as(m).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(0));
    }

    @Test
    void merchantHearsAboutThePendingRefundAndItsFailure() {
        // Arrange
        var m = Api.newMerchant(Api.SINK);
        String id = paidWithCard(m, FAILING_REFUND_CARD);
        String refundId = refund(m, id, Map.of());
        refunds.settleDue(Instant.now().plus(Duration.ofMinutes(2)));
        WebhookSink.RECEIVED.clear();

        // Act
        dispatcher.dispatchDue();

        // Assert — o estorno pendente e, depois, a falha
        var events = WebhookSink.RECEIVED.stream()
                .map(r -> JsonPath.from(r.body()))
                .filter(e -> e.getString("type").startsWith("refund.") && id.equals(e.getString("data.paymentId")))
                .toList();
        assertEquals(2, events.size());
        assertEquals("refund.pending", events.get(0).getString("type"));
        assertEquals("refund.failed", events.get(1).getString("type"));
        assertEquals(refundId, events.get(1).getString("data.id"));
    }
}
