package org.leonardo.standardpay.dashboard;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;

import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PanelTest {

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    /** O navegador depois de entrar: o cookie da sessão em toda requisição, sem seguir redirecionamentos. */
    private record Browser(String cookie) {
        RequestSpecification request() {
            return given().redirects().follow(false).cookie("sp_painel", cookie);
        }

        /** O token anti-CSRF, lido da página como o navegador leria. */
        String csrf() {
            var matcher = CSRF.matcher(request().get("/painel").then().statusCode(200).extract().asString());
            assertTrue(matcher.find(), "a página não tem o token do formulário");
            return matcher.group(1);
        }
    }

    private static Browser login(Api.Merchant m) {
        String cookie = given().redirects().follow(false).formParam("apiKey", m.apiKey()).post("/painel/entrar")
                .then().statusCode(303).extract().cookie("sp_painel");
        return new Browser(cookie);
    }

    private static String paidPayment(Api.Merchant m) {
        String id = Api.newPayment(m);
        given().redirects().follow(false).post("/checkout/" + id + "/pix").then().statusCode(303);
        return id;
    }

    // ── Entrar e sair ──

    @Test
    void withoutASessionGoesToTheLoginPage() {
        // Act
        var response = given().redirects().follow(false).get("/painel/cobrancas");

        // Assert
        response.then().statusCode(303).header("Location", endsWith("/painel/entrar"));
    }

    @Test
    void wrongApiKeyDoesNotLogIn() {
        // Act
        var response = given().redirects().follow(false).formParam("apiKey", "sk_test_errada").post("/painel/entrar");

        // Assert
        response.then().statusCode(401)
                .body(containsString("Chave de API inválida"))
                .header("Set-Cookie", nullValue());
    }

    @Test
    void loginSetsAProtectedSessionCookie() {
        // Arrange
        var m = Api.newMerchant();

        // Act
        var response = given().redirects().follow(false).formParam("apiKey", m.apiKey()).post("/painel/entrar");

        // Assert — o cookie nunca é a chave: é um token de sessão, inacessível a scripts
        response.then().statusCode(303)
                .header("Location", endsWith("/painel"))
                .header("Set-Cookie", allOf(containsString("HttpOnly"), containsStringIgnoringCase("SameSite=Lax"),
                        containsString("Path=/painel"), not(containsString(m.apiKey()))));
    }

    @Test
    void homeShowsTheMerchantsPaymentsAndProtectiveHeaders() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);
        var browser = login(m);

        // Act
        var response = browser.request().get("/painel");

        // Assert
        response.then().statusCode(200)
                .header("X-Frame-Options", "DENY")
                .header("Cache-Control", "no-store")
                .body(containsString("Test Shop"))
                .body(containsString("/painel/cobrancas/" + id))
                .body(containsString("R$ 195,00"));
    }

    @Test
    void logoutEndsTheSession() {
        // Arrange
        var browser = login(Api.newMerchant());
        String csrf = browser.csrf();

        // Act
        browser.request().formParam("csrf", csrf).post("/painel/sair").then().statusCode(303);

        // Assert — o mesmo cookie já não abre nada
        browser.request().get("/painel").then().statusCode(303).header("Location", endsWith("/painel/entrar"));
    }

    // ── Ações ──

    @Test
    void refundFromThePanel() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);
        var browser = login(m);
        String csrf = browser.csrf();

        // Act — R$ 50,00, digitado como uma pessoa digitaria
        var response = browser.request().formParam("csrf", csrf).formParam("valor", "50,00")
                .post("/painel/cobrancas/" + id + "/estornar");

        // Assert
        response.then().statusCode(303).header("Location", containsString("ok=estorno"));

        // Verify
        Api.as(m).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(5000));
    }

    @Test
    void actionsWithoutTheFormTokenAreRefused() {
        // Arrange — outro site faria o navegador postar com o cookie, mas sem o token
        var m = Api.newMerchant();
        String id = paidPayment(m);
        var browser = login(m);

        // Act
        var response = browser.request().formParam("valor", "195,00").post("/painel/cobrancas/" + id + "/estornar");

        // Assert
        response.then().statusCode(403);

        // Verify
        Api.as(m).get("/v1/payments/" + id).then().body("amountRefunded", equalTo(0));
    }

    @Test
    void refundErrorsComeBackInPortuguese() {
        // Arrange
        var m = Api.newMerchant();
        String id = paidPayment(m);
        var browser = login(m);
        String csrf = browser.csrf();
        String location = browser.request().formParam("csrf", csrf).formParam("valor", "500")
                .post("/painel/cobrancas/" + id + "/estornar")
                .then().statusCode(303).extract().header("Location");

        // Act
        var response = browser.request().get(location);

        // Assert
        response.then().statusCode(200).body(containsString("O valor passa do que ainda pode ser estornado."));
    }

    @Test
    void anotherMerchantsPaymentIsNotShown() {
        // Arrange
        String id = paidPayment(Api.newMerchant());
        var intruder = login(Api.newMerchant());

        // Act
        var response = intruder.request().get("/painel/cobrancas/" + id);

        // Assert
        response.then().statusCode(404);
    }

    @Test
    void createsARecipientAndShowsItsStatement() {
        // Arrange
        var m = Api.newMerchant();
        var browser = login(m);
        String csrf = browser.csrf();
        browser.request().formParam("csrf", csrf).formParam("nome", "Guia Ana").post("/painel/recebedores")
                .then().statusCode(303);
        String recipientId = Api.as(m).get("/v1/recipients").then().extract().path("[0].id");
        String id = Api.as(m).body(Map.of("amount", 10000,
                        "splits", java.util.List.of(Map.of("recipientId", recipientId, "amount", 7000))))
                .post("/v1/payments").then().statusCode(201).extract().path("id");
        given().post("/checkout/" + id + "/pix").then().statusCode(200);

        // Act
        var response = browser.request().get("/painel/recebedores/" + recipientId);

        // Assert
        response.then().statusCode(200)
                .body(containsString("Guia Ana"))
                .body(containsString("Recebimento"))
                .body(containsString("R$ 70,00"));
    }
}
