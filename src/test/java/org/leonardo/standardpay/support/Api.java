package org.leonardo.standardpay.support;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** Atalhos para os testes falarem com a API como um lojista falaria. */
public final class Api {

    public static final String SINK = "http://localhost:8081/test-sink";

    private Api() {
    }

    public record Merchant(String id, String apiKey, String webhookSecret) {
    }

    public static Merchant newMerchant(String webhookUrl) {
        var body = new HashMap<String, Object>();
        body.put("name", "Test Shop");
        if (webhookUrl != null) body.put("webhookUrl", webhookUrl);
        var r = given().contentType(ContentType.JSON).body(body)
                .post("/v1/merchants")
                .then().statusCode(201).extract();
        return new Merchant(r.path("id"), r.path("apiKey"), r.path("webhookSecret"));
    }

    public static Merchant newMerchant() {
        return newMerchant(null);
    }

    public static RequestSpecification as(Merchant m) {
        return given().header("Authorization", "Bearer " + m.apiKey()).contentType(ContentType.JSON);
    }

    /** Cobrança de R$ 195,00 com volta para uma loja fictícia; devolve o id. */
    public static String newPayment(Merchant m) {
        return as(m).body(Map.of(
                        "amount", 19500,
                        "description", "Passeio em grupo — Belém histórica",
                        "metadata", Map.of("tourId", "42"),
                        "returnUrl", "https://shop.example/paid",
                        "cancelUrl", "https://shop.example/gave-up"))
                .post("/v1/payments")
                .then().statusCode(201).extract().path("id");
    }
}
