package org.leonardo.standardpay.balances;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.balances.control.Ledger;
import org.leonardo.standardpay.refunds.control.Refunds;
import org.leonardo.standardpay.support.Api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BalancesTest {

    @Inject
    Ledger ledger;

    @Inject
    Refunds refunds;

    private static String newRecipient(Api.Merchant m, String name) {
        return Api.as(m).body(Map.of("name", name)).post("/v1/recipients")
                .then().statusCode(201).extract().path("id");
    }

    /** Partes na ordem dada: recebedor → centavos. */
    private static Map<String, Long> parts(Object... recipientAndAmount) {
        var map = new LinkedHashMap<String, Long>();
        for (int i = 0; i < recipientAndAmount.length; i += 2) {
            map.put((String) recipientAndAmount[i], ((Number) recipientAndAmount[i + 1]).longValue());
        }
        return map;
    }

    private static Response createSplitPayment(Api.Merchant m, long amount, Map<String, Long> splits) {
        var list = splits.entrySet().stream()
                .map(e -> Map.of("recipientId", e.getKey(), "amount", e.getValue()))
                .toList();
        return Api.as(m).body(Map.of("amount", amount, "splits", list)).post("/v1/payments");
    }

    private static String paidSplitPayment(Api.Merchant m, long amount, Map<String, Long> splits) {
        String id = createSplitPayment(m, amount, splits).then().statusCode(201).extract().path("id");
        given().post("/checkout/" + id + "/pix").then().statusCode(200);
        return id;
    }

    private static void refund(Api.Merchant m, String paymentId, long amount) {
        Api.as(m).body(Map.of("amount", amount)).post("/v1/payments/" + paymentId + "/refunds")
                .then().statusCode(201);
    }

    /** Disponível + pendente em BRL; 0 se a conta nunca recebeu nada. */
    private long net(String merchantId, String recipientId) {
        return ledger.balance(merchantId, recipientId, Instant.now()).stream()
                .filter(t -> t.currency().equals("BRL"))
                .mapToLong(t -> t.available() + t.pending())
                .sum();
    }

    // ── Recebedores ──

    @Test
    void createsARecipient() {
        // Arrange
        var m = Api.newMerchant();

        // Act
        var response = Api.as(m).body(Map.of("name", "Guia Ana", "metadata", Map.of("guideId", "7")))
                .post("/v1/recipients");

        // Assert
        response.then().statusCode(201)
                .body("id", startsWith("rcp_"))
                .body("name", equalTo("Guia Ana"))
                .body("metadata.guideId", equalTo("7"));
    }

    @Test
    void anotherMerchantsRecipientDoesNotExist() {
        // Arrange
        var owner = Api.newMerchant();
        var other = Api.newMerchant();
        String id = newRecipient(owner, "Guia Ana");

        // Act
        var response = Api.as(other).get("/v1/recipients/" + id + "/balance");

        // Assert
        response.then().statusCode(404);
    }

    // ── Cobrança dividida ──

    @Test
    void paymentShowsItsSplits() {
        // Arrange
        var m = Api.newMerchant();
        String guide = newRecipient(m, "Guia Ana");

        // Act
        var response = createSplitPayment(m, 10000, parts(guide, 7000));

        // Assert
        response.then().statusCode(201)
                .body("splits[0].recipientId", equalTo(guide))
                .body("splits[0].amount", equalTo(7000));
    }

    @Test
    void splitsCannotAddUpToMoreThanThePayment() {
        // Arrange
        var m = Api.newMerchant();
        String a = newRecipient(m, "A");
        String b = newRecipient(m, "B");

        // Act
        var response = createSplitPayment(m, 10000, parts(a, 6000, b, 5000));

        // Assert
        response.then().statusCode(400).body("error.code", equalTo("invalid_split"));
    }

    @Test
    void cannotSplitWithAnotherMerchantsRecipient() {
        // Arrange
        var owner = Api.newMerchant();
        var other = Api.newMerchant();
        String foreign = newRecipient(other, "De outro lojista");

        // Act
        var response = createSplitPayment(owner, 10000, parts(foreign, 1000));

        // Assert
        response.then().statusCode(400).body("error.code", equalTo("invalid_split"));
    }

    // ── Saldo e extrato ──

    @Test
    void paidSplitPaymentCreditsEachAccountAsPending() {
        // Arrange
        var m = Api.newMerchant();
        String guide = newRecipient(m, "Guia Ana");
        String id = createSplitPayment(m, 10000, parts(guide, 7000)).then().extract().path("id");

        // Act
        given().post("/checkout/" + id + "/pix").then().statusCode(200);

        // Assert — D+1: tudo ainda pendente
        Api.as(m).get("/v1/recipients/" + guide + "/balance").then()
                .body("account", equalTo(guide))
                .body("balances[0].currency", equalTo("BRL"))
                .body("balances[0].pending", equalTo(7000))
                .body("balances[0].available", equalTo(0));

        // Verify — o resto é do lojista
        Api.as(m).get("/v1/balance").then()
                .body("account", equalTo(m.id()))
                .body("balances[0].pending", equalTo(3000));
    }

    @Test
    void creditsBecomeAvailableAfterTheDelay() {
        // Arrange
        var m = Api.newMerchant();
        String guide = newRecipient(m, "Guia Ana");
        paidSplitPayment(m, 10000, parts(guide, 7000));

        // Act — dois dias depois
        var later = ledger.balance(m.id(), guide, Instant.now().plus(Duration.ofDays(2)));

        // Assert
        assertEquals(7000, later.get(0).available());
        assertEquals(0, later.get(0).pending());
    }

    @Test
    void statementListsWhatEachAccountReceived() {
        // Arrange
        var m = Api.newMerchant();
        String guide = newRecipient(m, "Guia Ana");
        String id = paidSplitPayment(m, 10000, parts(guide, 7000));

        // Act
        var response = Api.as(m).get("/v1/recipients/" + guide + "/transactions");

        // Assert
        response.then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", startsWith("txn_"))
                .body("[0].type", equalTo("payment"))
                .body("[0].amount", equalTo(7000))
                .body("[0].status", equalTo("pending"))
                .body("[0].paymentId", equalTo(id));
    }

    // ── Estornos de cobrança dividida ──

    @Test
    void refundComesOutOfEachAccountInProportion() {
        // Arrange — 30% para A, 20% para B, 50% fica com o lojista
        var m = Api.newMerchant();
        String a = newRecipient(m, "A");
        String b = newRecipient(m, "B");
        String id = paidSplitPayment(m, 10000, parts(a, 3000, b, 2000));

        // Act — metade
        refund(m, id, 5000);

        // Assert
        assertEquals(1500, net(m.id(), a));
        assertEquals(1000, net(m.id(), b));
        assertEquals(2500, net(m.id(), null));

        // Verify — o débito aparece no extrato, ligado ao estorno
        Api.as(m).get("/v1/recipients/" + a + "/transactions").then()
                .body("[0].type", equalTo("refund"))
                .body("[0].amount", equalTo(-1500))
                .body("[0].refundId", startsWith("re_"));
    }

    @Test
    void smallRefundsDoNotLoseCents() {
        // Arrange — 1/3 para cada conta: nenhum estorno de poucos centavos divide certinho
        var m = Api.newMerchant();
        String a = newRecipient(m, "A");
        String b = newRecipient(m, "B");
        String id = paidSplitPayment(m, 300, parts(a, 100, b, 100));
        for (long amount : new long[]{1, 1, 7, 50, 100, 40}) {
            refund(m, id, amount);
        }

        // Act — o que resta: 101 centavos
        refund(m, id, 101);

        // Assert — estornado tudo, cada conta devolveu exatamente o que recebeu
        assertEquals(List.of(0L, 0L, 0L), List.of(net(m.id(), a), net(m.id(), b), net(m.id(), null)));
    }

    @Test
    void failedRefundGivesEachShareBack() {
        // Arrange — pago com o cartão cujo estorno falha
        var m = Api.newMerchant();
        String guide = newRecipient(m, "Guia Ana");
        String id = createSplitPayment(m, 10000, parts(guide, 7000)).then().extract().path("id");
        given().redirects().follow(false).formParam("cardNumber", "4000000000005126").post("/checkout/" + id + "/card");
        Api.as(m).body(Map.of()).post("/v1/payments/" + id + "/refunds").then().statusCode(201);

        // Act
        refunds.settleDue(Instant.now().plus(Duration.ofMinutes(2)));

        // Assert
        assertEquals(7000, net(m.id(), guide));
        assertEquals(3000, net(m.id(), null));

        // Verify — o extrato mostra a ida e a volta
        Api.as(m).get("/v1/recipients/" + guide + "/transactions").then()
                .body("type", containsInAnyOrder("payment", "refund", "refund_failure"));
    }
}
