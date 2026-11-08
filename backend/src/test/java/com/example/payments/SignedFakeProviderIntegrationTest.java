package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.payments.provider.ProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end with callback auth enabled: the fake provider signs, the filter verifies, and the
 * controller still receives the original body. Uses a test-only signing key.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.provider.auto-callback=true",
                "app.provider.min-delay-ms=50",
                "app.provider.max-delay-ms=100",
                "app.callback.auth.enabled=true",
                "app.callback.auth.secret=test-only-signing-key"
        })
class SignedFakeProviderIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProviderProperties providerProperties;

    @BeforeEach
    void pointProviderAtRandomPort() {
        providerProperties.setCallbackUrl("http://localhost:" + port + "/provider-callbacks");
    }

    @Test
    void signedCallbackMovesPaymentToSucceeded() {
        JsonNode created = createPayment();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            JsonNode payment = fetch(created.path("id").asText());
            assertThat(payment.path("status").asText()).isEqualTo("SUCCEEDED");
        });

        JsonNode payment = fetch(created.path("id").asText());
        assertThat(payment.path("providerStatus").asText()).isEqualTo("CAPTURED");
    }

    @Test
    void unsignedCallbackIsRejectedByTheRunningService() throws Exception {
        String body = """
                {"eventId":"evt-unsigned","providerPaymentId":"prov-unsigned",
                 "status":"CAPTURED","occurredAt":"2026-11-08T12:00:00Z"}
                """;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/provider-callbacks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_signature");
    }

    private JsonNode createPayment() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", new BigDecimal("23.45"));
        request.put("currency", "EUR");
        request.put("customerReference", "SIGNED-1");

        ResponseEntity<JsonNode> response = rest.postForEntity("/payments", request, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }

    private JsonNode fetch(String id) {
        ResponseEntity<JsonNode> response = rest.getForEntity("/payments/" + id, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
}
