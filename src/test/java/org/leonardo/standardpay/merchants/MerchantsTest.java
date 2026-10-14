package org.leonardo.standardpay.merchants;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.support.Api;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MerchantsTest {

    @Test
    void signupReturnsTheApiKeyOnlyOnce() {
        var m = Api.newMerchant("https://shop.example/webhooks");
        assertTrue(m.apiKey().startsWith("sk_test_"));
        assertTrue(m.webhookSecret().startsWith("whsec_"));

        Api.as(m).get("/v1/merchants/me").then()
                .statusCode(200)
                .body("id", equalTo(m.id()))
                .body("apiKeyLast4", equalTo(m.apiKey().substring(m.apiKey().length() - 4)))
                .body("$", not(hasKey("apiKey")));
    }

    @Test
    void missingKeyGets401InTheStandardErrorShape() {
        given().get("/v1/merchants/me").then()
                .statusCode(401)
                .body("error.code", equalTo("invalid_api_key"));
    }

    @Test
    void madeUpKeyGets401() {
        given().header("Authorization", "Bearer sk_test_madeup").get("/v1/payments").then()
                .statusCode(401);
    }

    @Test
    void rejectsNonHttpWebhookUrl() {
        given().contentType(ContentType.JSON).body(Map.of("name", "X", "webhookUrl", "file:///etc/passwd"))
                .post("/v1/merchants").then()
                .statusCode(400)
                .body("error.code", equalTo("invalid_url"));
    }

    @Test
    void validationErrorsNameTheFieldInEnglish() {
        given().contentType(ContentType.JSON).body(Map.of("webhookUrl", "https://x.example"))
                .post("/v1/merchants").then()
                .statusCode(400)
                .body("error.code", equalTo("invalid_request"))
                .body("error.message", equalTo("name: is required"));
    }

    @Test
    void rotatingTheSecretReplacesIt() {
        var m = Api.newMerchant("https://shop.example/webhooks");
        String rotated = Api.as(m).post("/v1/merchants/me/webhook-secret").then()
                .statusCode(200).extract().path("webhookSecret");
        assertNotEquals(m.webhookSecret(), rotated);
    }
}
