package org.leonardo.standardpay.refunds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RefundsTest {

    /** Cobrança de R$ 195,00 já paga com PIX. */
    private static String paidPayment(Api.Merchant m) {
        String id = Api.newPayment(m);
        given().redirects().follow(false).post("/checkout/" + id + "/pix").then().statusCode(303);
        return id;
    }

    private static void refund(Api.Merchant m, String paymentId, Map<String, ?> body) {
        Api.as(m).body(body).post("/v1/payments/" + paymentId + "/refunds").then().statusCode(201);
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
}
