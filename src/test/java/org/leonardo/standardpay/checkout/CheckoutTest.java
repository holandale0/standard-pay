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

    private static void payWithCard(String paymentId, String number) {
        browser().formParam("cardNumber", number).post("/checkout/" + paymentId + "/card");
    }

    @Test
    void pageShowsAmountInRealsAndTheSimulationNotice() {
        // Arrange
        String id = Api.newPayment(Api.newMerchant());

        // Act
        var response = browser().get("/checkout/" + id);

        // Assert
        response.then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("R$ 195,00"))
                .body(containsString("Test Shop"))
                .body(containsString("nenhum valor real é cobrado"));
    }

    @Test
    void approvedCardRedirectsBackToTheShop() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);

        // Act
        var response = browser().formParam("cardNumber", "4242 4242 4242 4242").post("/checkout/" + id + "/card");

        // Assert
        response.then()
                .statusCode(303)
                .header("Location", equalTo("https://shop.example/paid?payment_id=" + id + "&status=succeeded"));

        // Verify
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("method", equalTo("card"))
                .body("cardLast4", equalTo("4242"))
                .body("paidAt", notNullValue());
    }

    @Test
    void declinedCardShowsTheReasonAndKeepsThePaymentOpen() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);

        // Act
        var response = browser().formParam("cardNumber", "4000000000009995").post("/checkout/" + id + "/card");

        // Assert
        response.then()
                .statusCode(200)
                .body(containsString("Saldo insuficiente"));

        // Verify
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("pending"))
                .body("failureCode", equalTo("insufficient_funds"));
    }

    @Test
    void anotherCardAfterADeclineIsApprovedAndClearsTheFailure() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        payWithCard(id, "4000000000009995");

        // Act
        var response = browser().formParam("cardNumber", "4242424242424242").post("/checkout/" + id + "/card");

        // Assert
        response.then().statusCode(303);

        // Verify
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("failureCode", nullValue());
    }

    @Test
    void pixSimulationPays() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);

        // Act
        var response = browser().post("/checkout/" + id + "/pix");

        // Assert
        response.then().statusCode(303);

        // Verify
        Api.as(m).get("/v1/payments/" + id).then()
                .body("status", equalTo("succeeded"))
                .body("method", equalTo("pix"));
    }

    @Test
    void paidPaymentCannotBePaidAgain() {
        // Arrange — pago com PIX; o pagador volta no navegador e tenta o cartão
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        browser().post("/checkout/" + id + "/pix").then().statusCode(303);

        // Act
        var response = browser().formParam("cardNumber", "4242424242424242").post("/checkout/" + id + "/card");

        // Assert
        response.then().statusCode(303);

        // Verify — continua registrado o primeiro pagamento, não o segundo
        Api.as(m).get("/v1/payments/" + id).then()
                .body("method", equalTo("pix"))
                .body("cardLast4", nullValue());
    }

    @Test
    void payerCancelGoesToTheCancelUrl() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);

        // Act
        var response = browser().post("/checkout/" + id + "/cancel");

        // Assert
        response.then()
                .statusCode(303)
                .header("Location", startsWith("https://shop.example/gave-up?payment_id=" + id));

        // Verify
        Api.as(m).get("/v1/payments/" + id).then().body("status", equalTo("canceled"));
    }

    @Test
    void overduePaymentShowsAsExpiredWhenOpened() {
        // Arrange
        var m = Api.newMerchant();
        String id = Api.newPayment(m);
        QuarkusTransaction.requiringNew().run(() ->
                Payment.<Payment>findById(id).expiresAt = Instant.now().minusSeconds(1));

        // Act
        var response = browser().get("/checkout/" + id);

        // Assert
        response.then().statusCode(200).body(containsString("expirou"));

        // Verify — abrir a página já expira, sem esperar a varredura
        Api.as(m).get("/v1/payments/" + id).then().body("status", equalTo("expired"));
    }

    @Test
    void unknownPaymentIs404() {
        // Act
        var response = browser().get("/checkout/pay_doesnotexist");

        // Assert
        response.then().statusCode(404);
    }
}
