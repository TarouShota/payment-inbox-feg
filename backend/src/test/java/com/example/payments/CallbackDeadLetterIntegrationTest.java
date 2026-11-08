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
import org.springframework.http.ResponseEntity;

/**
 * The provider endpoint is deliberately unreachable. The relay must retry, then park the row as
 * DEAD after the configured attempts, while the payment itself stays PENDING.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.provider.auto-callback=true",
                "app.provider.min-delay-ms=0",
                "app.provider.max-delay-ms=0",
                "app.provider.max-attempts=2",
                "app.provider.initial-retry-ms=10",
                "app.provider.max-retry-ms=20",
                "app.provider.connect-timeout-ms=200",
                "app.provider.read-timeout-ms=200"
        })
class CallbackDeadLetterIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProviderProperties providerProperties;

    @Autowired
    private CallbackOutboxRepository outbox;

    @BeforeEach
    void pointProviderAtClosedPort() {
        providerProperties.setCallbackUrl("http://127.0.0.1:1/provider-callbacks");
    }

    @Test
    void failedDeliveryBecomesDeadAndPaymentStaysPending() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", new BigDecimal("9.99"));
        request.put("currency", "EUR");
        request.put("customerReference", "DEAD-LETTER-1");

        ResponseEntity<JsonNode> created = rest.postForEntity("/payments", request, JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String paymentId = created.getBody().path("id").asText();
        String providerPaymentId = created.getBody().path("providerPaymentId").asText();

        await().atMost(Duration.ofSeconds(10)).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            var rows = outbox.findAll().stream()
                    .filter(row -> row.getProviderPaymentId().equals(providerPaymentId))
                    .toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getState()).isEqualTo(CallbackDeliveryState.DEAD);
        });

        var row = outbox.findAll().stream()
                .filter(item -> item.getProviderPaymentId().equals(providerPaymentId))
                .findFirst()
                .orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getLastError()).isNotBlank();

        JsonNode payment = rest.getForObject("/payments/" + paymentId, JsonNode.class);
        assertThat(payment.path("status").asText()).isEqualTo("PENDING");
        assertThat(payment.path("providerStatus").isNull()).isTrue();
        assertThat(payment.path("lastCallbackOccurredAt").isNull()).isTrue();
    }
}
