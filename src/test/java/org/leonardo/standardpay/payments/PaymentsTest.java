package org.leonardo.standardpay.payments;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PaymentsTest {

    @Test
    void createdPaymentIsPendingWithACheckoutUrl() {
        var m = Api.newMerchant();
        Api.as(m).body(Map.of("amount", 19500, "metadata", Map.of("tourId", "42")))
                .post("/v1/payments").then()
                .statusCode(201)
                .body("id", startsWith("pay_"))
                .body("status", equalTo("pending"))
                .body("amount", equalTo(19500))
                .body("currency", equalTo("BRL"))
                .body("metadata.tourId", equalTo("42"))
                .body("checkoutUrl", matchesPattern("http://localhost:8081/checkout/pay_\\w+"));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSamePayment() {
        var m = Api.newMerchant();
        String first = Api.as(m).header("Idempotency-Key", "order-7").body(Map.of("amount", 1000))
                .post("/v1/payments").then().statusCode(201).extract().path("id");
        String second = Api.as(m).header("Idempotency-Key", "order-7").body(Map.of("amount", 1000))
                .post("/v1/payments").then().statusCode(201).extract().path("id");
        assertEquals(first, second);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOnePayment() {
        var m = Api.newMerchant();
        var requests = List.of(1, 2, 3, 4).stream()
                .map(i -> CompletableFuture.supplyAsync(() ->
                        Api.as(m).header("Idempotency-Key", "double-click").body(Map.of("amount", 1000))
                                .post("/v1/payments").then().statusCode(201).extract().<String>path("id")))
                .toList();
        var ids = requests.stream().map(CompletableFuture::join).distinct().toList();
        assertEquals(1, ids.size(), "every request must get the same payment");
    }

    @Test
    void merchantCannotSeeAnotherMerchantsPayment() {
        var owner = Api.newMerchant();
        var other = Api.newMerchant();
        String id = Api.newPayment(owner);
        Api.as(other).get("/v1/payments/" + id).then()
                .statusCode(404)
                .body("error.code", equalTo("resource_not_found"));
    }

    @Test
    void amountIsInCentsAndMustBePositive() {
        var m = Api.newMerchant();
        Api.as(m).body(Map.of("amount", 0)).post("/v1/payments").then()
                .statusCode(400)
                .body("error.message", containsString("amount: must be at least 1 (amounts are in cents)"));
    }

    @Test
    void rejectsReturnUrlThatIsNotHttp() {
        var m = Api.newMerchant();
        Api.as(m).body(Map.of("amount", 100, "returnUrl", "javascript:alert(1)")).post("/v1/payments").then()
                .statusCode(400)
                .body("error.code", equalTo("invalid_url"));
    }

    @Test
    void merchantCanCancelOnlyWhilePending() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        Api.as(m).post("/v1/payments/" + id + "/cancel").then().statusCode(200).body("status", equalTo("canceled"));
        // Cancelar de novo não é erro: o resultado pedido já vale.
        Api.as(m).post("/v1/payments/" + id + "/cancel").then().statusCode(200).body("status", equalTo("canceled"));
    }

    @Test
    void listFiltersByStatus() {
        var m = Api.newMerchant();
        Api.newPayment(m);
        String canceled = Api.newPayment(m);
        Api.as(m).post("/v1/payments/" + canceled + "/cancel").then().statusCode(200);

        Api.as(m).get("/v1/payments?status=canceled").then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(canceled));
    }
}
