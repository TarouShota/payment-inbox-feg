package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.api.PaymentController;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.ProcessedCallbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.provider.auto-callback=false")
@AutoConfigureMockMvc
class IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private ProcessedCallbackRepository processedCallbacks;

    @BeforeEach
    void cleanDatabase() {
        processedCallbacks.deleteAll();
        payments.deleteAll();
    }

    @Test
    void replayingTheSameRequestReturnsTheOriginalPayment() throws Exception {
        MvcResult first = create("key-1", "10.00", "EUR", "REF-1");
        MvcResult second = create("key-1", "10.00", "EUR", "REF-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader(PaymentController.IDEMPOTENCY_REPLAYED_HEADER))
                .isNull();

        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getHeader(PaymentController.IDEMPOTENCY_REPLAYED_HEADER))
                .isEqualTo("true");

        JsonNode original = body(first);
        JsonNode replay = body(second);
        assertThat(replay.path("id").asText()).isEqualTo(original.path("id").asText());
        assertThat(replay.path("providerPaymentId").asText())
                .isEqualTo(original.path("providerPaymentId").asText());
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void equivalentAmountsAndCurrencyCaseReplayTheSamePayment() throws Exception {
        MvcResult first = create("key-1", "10.0", "eur", "REF-1");
        MvcResult second = create("key-1", "10.00", "EUR", "REF-1");

        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(second).path("id").asText())
                .isEqualTo(body(first).path("id").asText());
        assertThat(body(first).path("currency").asText()).isEqualTo("EUR");
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForDifferentDataIsAConflict() throws Exception {
        create("key-1", "10.00", "EUR", "REF-1");

        mockMvc.perform(post("/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("11.00", "EUR", "REF-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_key_reuse"));

        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentPayments() throws Exception {
        MvcResult first = create("key-1", "10.00", "EUR", "REF-1");
        MvcResult second = create("key-2", "10.00", "EUR", "REF-1");

        assertThat(body(second).path("id").asText())
                .isNotEqualTo(body(first).path("id").asText());
        assertThat(payments.count()).isEqualTo(2);
    }

    @Test
    void requestsWithoutAKeyStillCreateEveryTime() throws Exception {
        create(null, "10.00", "EUR", "REF-1");
        create(null, "10.00", "EUR", "REF-1");

        assertThat(payments.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "key with space", "bad/key!"})
    void malformedKeysAreRejected(String key) throws Exception {
        mockMvc.perform(post("/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("10.00", "EUR", "REF-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));

        assertThat(payments.count()).isZero();
    }

    @Test
    void concurrentRetriesCreateOnePayment() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> request = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return create("race-key", "10.00", "EUR", "REF-RACE");
            };
            Future<MvcResult> first = pool.submit(request);
            Future<MvcResult> second = pool.submit(request);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = new ArrayList<>(List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));

            assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
            long replays = results.stream()
                    .filter(r -> "true".equals(
                            r.getResponse().getHeader(PaymentController.IDEMPOTENCY_REPLAYED_HEADER)))
                    .count();
            assertThat(replays).isEqualTo(1);
            assertThat(payments.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private MvcResult create(String key, String amount, String currency, String reference)
            throws Exception {
        var request = post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson(amount, currency, reference));
        if (key != null) {
            request.header(PaymentController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String paymentJson(String amount, String currency, String reference) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("currency", currency);
        body.put("customerReference", reference);
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
