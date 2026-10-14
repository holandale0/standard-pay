package org.leonardo.standardpay.checkout;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.payments.entity.Payment;
import org.leonardo.standardpay.support.Api;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** A página do pagador, de ponta a ponta, como um navegador a usaria. */
@QuarkusTest
class CheckoutTest {

    /** Como um navegador, mas sem seguir redirecionamentos: eles são o que se testa. */
    private static RequestSpecification browser() {
        return given().redirects().follow(false);
    }

    @Test
    void pageShowsAmountInRealsAndTheSimulationNotice() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().get("/checkout/" + id).then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("R$ 195,00"))
                .body(containsString("Test Shop"))
                .body(containsString("nenhum valor real é cobrado"));
    }

    @Test
    void approvedCardRedirectsBackToTheShop() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().formParam("cardNumber", "4242 4242 4242 4242").post("/checkout/" + id + "/card").then()
                .statusCode(303)
                .header("Location", equalTo("https://shop.example/paid?payment_id=" + id + "&status=succeeded"));

        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("method", equalTo("card"))
                .body("cardLast4", equalTo("4242"))
                .body("paidAt", notNullValue());
    }

    @Test
    void declinedCardStaysPendingSoThePayerCanTryAgain() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().formParam("cardNumber", "4000000000009995").post("/checkout/" + id + "/card").then()
                .statusCode(200)
                .body(containsString("Saldo insuficiente"));

        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("pending"))
                .body("failureCode", equalTo("insufficient_funds"));

        // Segunda tentativa, com outro cartão: aprova e limpa a recusa anterior.
        browser().formParam("cardNumber", "4242424242424242").post("/checkout/" + id + "/card").then()
                .statusCode(303);
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("failureCode", nullValue());
    }

    @Test
    void pixSimulationPays() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().post("/checkout/" + id + "/pix").then().statusCode(303);
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("method", equalTo("pix"));
    }

    @Test
    void paidPaymentCannotBePaidAgain() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().post("/checkout/" + id + "/pix").then().statusCode(303);
        // Voltar no navegador e pagar de novo: a página só mostra o recibo.
        browser().formParam("cardNumber", "4242424242424242").post("/checkout/" + id + "/card").then()
                .statusCode(303);
        Api.as(m).get("/v1/payments/" + id).then().body("method", equalTo("pix"));
    }

    @Test
    void payerCancelGoesToTheCancelUrl() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().post("/checkout/" + id + "/cancel").then()
                .statusCode(303)
                .header("Location", startsWith("https://shop.example/gave-up?payment_id=" + id));
        Api.as(m).get("/v1/payments/" + id).then().body("status", equalTo("canceled"));
    }

    @Test
    void overduePaymentShowsAsExpiredWhenOpened() {
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        QuarkusTransaction.requiringNew().run(() ->
                Payment.<Payment>findById(id).expiresAt = Instant.now().minusSeconds(1));

        browser().get("/checkout/" + id).then().statusCode(200).body(containsString("expirou"));
        Api.as(m).get("/v1/payments/" + id).then().body("status", equalTo("expired"));
    }

    @Test
    void unknownPaymentIs404() {
        browser().get("/checkout/pay_doesnotexist").then().statusCode(404);
    }
}
