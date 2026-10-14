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
    void signupReturnsCredentialsWithTheirPrefixes() {
        // Act
        var m = Api.newMerchant("https://shop.example/webhooks");

        // Assert
        assertTrue(m.apiKey().startsWith("sk_test_"));
        assertTrue(m.webhookSecret().startsWith("whsec_"));
    }

    @Test
    void theApiKeyIsNeverShownAgainAfterSignup() {
        // Arrange
        var m = Api.newMerchant("https://shop.example/webhooks");

        // Act
        var response = Api.as(m).get("/v1/merchants/me");

        // Assert
        response.then()
                .statusCode(200)
                .body("id", equalTo(m.id()))
                .body("apiKeyLast4", equalTo(m.apiKey().substring(m.apiKey().length() - 4)))
                .body("$", not(hasKey("apiKey")));
    }

    @Test
    void missingKeyGets401InTheStandardErrorShape() {
        // Act
        var response = given().get("/v1/merchants/me");

        // Assert
        response.then()
                .statusCode(401)
                .body("error.code", equalTo("invalid_api_key"));
    }

    @Test
    void madeUpKeyGets401() {
        // Act
        var response = given().header("Authorization", "Bearer sk_test_madeup").get("/v1/payments");

        // Assert
        response.then().statusCode(401);
    }

    @Test
    void rejectsNonHttpWebhookUrl() {
        // Arrange
        var body = Map.of("name", "X", "webhookUrl", "file:///etc/passwd");

        // Act
        var response = given().contentType(ContentType.JSON).body(body).post("/v1/merchants");

        // Assert
        response.then()
                .statusCode(400)
                .body("error.code", equalTo("invalid_url"));
    }

    @Test
    void validationErrorsNameTheFieldInEnglish() {
        // Arrange
        var bodyWithoutName = Map.of("webhookUrl", "https://x.example");

        // Act
        var response = given().contentType(ContentType.JSON).body(bodyWithoutName).post("/v1/merchants");

        // Assert
        response.then()
                .statusCode(400)
                .body("error.code", equalTo("invalid_request"))
                .body("error.message", equalTo("name: is required"));
    }

    @Test
    void rotatingTheSecretReplacesIt() {
        // Arrange
        var m = Api.newMerchant("https://shop.example/webhooks");

        // Act
        String rotated = Api.as(m).post("/v1/merchants/me/webhook-secret")
                .then().statusCode(200).extract().path("webhookSecret");

        // Assert
        assertNotEquals(m.webhookSecret(), rotated);

        // Verify
        Api.as(m).get("/v1/merchants/me").then().body("webhookSecret", equalTo(rotated));
    }
}
