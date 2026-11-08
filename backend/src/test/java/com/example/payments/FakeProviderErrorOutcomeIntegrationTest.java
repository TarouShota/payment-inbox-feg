package com.example.payments;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
 * Same wiring as {@link FakeProviderIntegrationTest}, with the outcome configured to ERROR:
 * the provider reports a value that is neither a known nor a terminal outcome, so the payment
 * becomes UNKNOWN.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.provider.auto-callback=true",
                "app.provider.outcome=ERROR",
                "app.provider.min-delay-ms=50",
                "app.provider.max-delay-ms=100"
        })
class FakeProviderErrorOutcomeIntegrationTest {

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
    void configuredErrorOutcomeEndsInUnknown() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", new BigDecimal("7.00"));
        request.put("currency", "USD");
        request.put("customerReference", "INTEGRATION-ERROR");

        ResponseEntity<JsonNode> created = rest.postForEntity("/payments", request, JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        String id = created.getBody().path("id").asText();

        await().atMost(Duration.ofSeconds(5)).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            JsonNode payment = rest.getForObject("/payments/" + id, JsonNode.class);
            assertThat(payment.path("status").asText()).isEqualTo("UNKNOWN");
        });

        JsonNode payment = rest.getForObject("/payments/" + id, JsonNode.class);
        assertThat(payment.path("providerStatus").asText()).isEqualTo("ERROR");
        assertThat(payment.path("lastCallbackOccurredAt").isNull()).isFalse();
    }
}
