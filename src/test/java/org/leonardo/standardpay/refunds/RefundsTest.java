package org.leonardo.standardpay.refunds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RefundsTest {

    private static String paidPayment(Api.Merchant m) {
        String id = Api.newPayment(m);
        given().redirects().follow(false).post("/checkout/" + id + "/pix").then().statusCode(303);
        return id;
    }

    @Test
    void partialRefundsAddUpToTheFullAmount() {
        var m = Api.newMerchant();
        String id = paidPayment(m);

        Api.as(m).body(Map.of("amount", 5000, "reason", "Uma pessoa a menos")).post("/v1/payments/" + id + "/refunds")
                .then().statusCode(201)
                .body("id", startsWith("re_"))
                .body("amount", equalTo(5000))
                .body("payment.amountRefunded", equalTo(5000));

        // Sem valor: estorna o que resta.
        Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds")
                .then().statusCode(201)
                .body("amount", equalTo(14500))
                .body("payment.amountRefunded", equalTo(19500));

        Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds")
                .then().statusCode(409).body("error.code", equalTo("already_refunded"));

        Api.as(m).get("/v1/payments/" + id + "/refunds").then().body("size()", equalTo(2));
    }

    @Test
    void cannotRefundMoreThanWasPaid() {
        var m = Api.newMerchant();
        String id = paidPayment(m);
        Api.as(m).body(Map.of("amount", 20000)).post("/v1/payments/" + id + "/refunds")
                .then().statusCode(400).body("error.code", equalTo("amount_too_large"));
    }

    @Test
    void cannotRefundAPendingPayment() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds")
                .then().statusCode(409).body("error.code", equalTo("payment_not_refundable"));
    }

    @Test
    void cannotRefundAnotherMerchantsPayment() {
        var owner = Api.newMerchant();
        var other = Api.newMerchant();
        String id = paidPayment(owner);
        Api.as(other).body(Map.of()).post("/v1/payments/" + id + "/refunds").then().statusCode(404);
    }
}
