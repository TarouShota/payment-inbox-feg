package com.example.payments;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.payments.provider.CallbackDeliveryState;
import com.example.payments.provider.CallbackOutboxRepository;
import com.example.payments.provider.ProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
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
 * End-to-end: the fake provider schedules its callback after commit and delivers it over real
 * HTTP to the running service. The default outcome (CAPTURED) ends in SUCCEEDED.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.provider.auto-callback=true",
                "app.provider.min-delay-ms=50",
                "app.provider.max-delay-ms=100"
        })
class FakeProviderIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProviderProperties providerProperties;

    @Autowired
    private CallbackOutboxRepository outbox;

    @BeforeEach
    void pointProviderAtRandomPort() {
        providerProperties.setCallbackUrl("http://localhost:" + port + "/provider-callbacks");
    }

    @Test
    void fakeProviderCallbackMovesPaymentToSucceeded() {
        JsonNode created = createPayment();

        await().atMost(Duration.ofSeconds(5)).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            JsonNode payment = fetch(created.path("id").asText());
            assertThat(payment.path("status").asText()).isEqualTo("SUCCEEDED");
        });

        JsonNode payment = fetch(created.path("id").asText());
        assertThat(payment.path("providerStatus").asText()).isEqualTo("CAPTURED");
        assertThat(payment.path("lastCallbackOccurredAt").isNull()).isFalse();
        String providerPaymentId = payment.path("providerPaymentId").asText();
        assertThat(providerPaymentId).startsWith("prov-");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(outbox.findAll())
                .anySatisfy(row -> {
                    assertThat(row.getProviderPaymentId()).isEqualTo(providerPaymentId);
                    assertThat(row.getState()).isEqualTo(CallbackDeliveryState.SENT);
                }));
    }

    private JsonNode createPayment() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", new BigDecimal("23.45"));
        request.put("currency", "EUR");
        request.put("customerReference", "INTEGRATION-1");

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
