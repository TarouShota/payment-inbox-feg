package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.ProcessedCallback;
import com.example.payments.domain.ProcessedCallbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two callbacks for the same payment arriving at the same time. The row lock must serialise
 * them; otherwise the read-check-write in CallbackService loses an update.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.provider.auto-callback=false")
class CallbackConcurrencyTest {

    private static final Instant T2 = Instant.parse("2026-01-01T10:00:02Z");
    private static final Instant T3 = Instant.parse("2026-01-01T10:00:03Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private ProcessedCallbackRepository processedCallbacks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        processedCallbacks.deleteAll();
        payments.deleteAll();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void newerCallbackWinsRegardlessOfArrivalOrder() throws Exception {
        Payment payment = seedPayment("prov-raced");

        List<String> results = race(
                () -> postCallback("evt-captured", payment.getProviderPaymentId(), "CAPTURED", T2),
                () -> postCallback("evt-declined", payment.getProviderPaymentId(), "DECLINED", T3));

        // Either order is valid (APPLIED/APPLIED or APPLIED/IGNORED); the final state is not.
        assertThat(results).allMatch(result -> List.of("APPLIED", "IGNORED").contains(result));

        Payment finalState = payments.findByProviderPaymentId("prov-raced").orElseThrow();
        assertThat(finalState.getStatus().name()).isEqualTo("FAILED");
        assertThat(finalState.getProviderStatus()).isEqualTo("DECLINED");
        assertThat(finalState.getLastCallbackOccurredAt()).isEqualTo(T3);
    }

    @Test
    void sameEventIdRacedTwiceIsAppliedOnce() throws Exception {
        Payment payment = seedPayment("prov-duplicated");

        List<String> results = race(
                () -> postCallback("evt-same", payment.getProviderPaymentId(), "CAPTURED", T2),
                () -> postCallback("evt-same", payment.getProviderPaymentId(), "CAPTURED", T2));

        assertThat(results).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(processedCallbacks.count()).isEqualTo(1);

        Payment finalState = payments.findByProviderPaymentId("prov-duplicated").orElseThrow();
        assertThat(finalState.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(finalState.getLastCallbackOccurredAt()).isEqualTo(T2);
    }

    @Test
    void sameEventIdAcrossTwoPaymentsIsAppliedOnce() throws Exception {
        Payment firstPayment = seedPayment("prov-shared-a");
        Payment secondPayment = seedPayment("prov-shared-b");

        List<String> results = race(
                () -> postCallback("evt-shared", firstPayment.getProviderPaymentId(), "CAPTURED", T2),
                () -> postCallback("evt-shared", secondPayment.getProviderPaymentId(), "CAPTURED", T2));

        assertThat(results).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        assertThat(processedCallbacks.count()).isEqualTo(1);

        List<Payment> all = payments.findAll();
        assertThat(all).filteredOn(payment -> payment.getStatus().name().equals("SUCCEEDED")).hasSize(1);
        assertThat(all).filteredOn(payment -> payment.getStatus().name().equals("PENDING")).hasSize(1);

        Payment applied = all.stream()
                .filter(payment -> payment.getStatus().name().equals("SUCCEEDED"))
                .findFirst()
                .orElseThrow();
        assertThat(processedCallbacks.findById("evt-shared").orElseThrow().getPaymentId())
                .isEqualTo(applied.getId());
    }

    @Test
    void duplicateEventInsertDoesNotUpdateTheWinnersRow() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String eventId = "evt-duplicate-" + UUID.randomUUID();
        UUID firstPayment = UUID.randomUUID();
        UUID secondPayment = UUID.randomUUID();

        transaction.executeWithoutResult(status -> processedCallbacks.saveAndFlush(
                new ProcessedCallback(eventId, firstPayment, Instant.now())));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> processedCallbacks.saveAndFlush(
                new ProcessedCallback(eventId, secondPayment, Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(processedCallbacks.findById(eventId).orElseThrow().getPaymentId())
                .isEqualTo(firstPayment);
    }

    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<String> a = executor.submit(gated(barrier, first));
        Future<String> b = executor.submit(gated(barrier, second));
        return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }

    private Callable<String> gated(CyclicBarrier barrier, Callable<String> action) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return action.call();
        };
    }

    private String postCallback(String eventId, String providerPaymentId, String status, Instant occurredAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("providerPaymentId", providerPaymentId);
        body.put("occurredAt", occurredAt.toString());
        body.put("status", status);

        ResponseEntity<JsonNode> response =
                rest.postForEntity("/provider-callbacks", body, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("result").asText();
    }

    private Payment seedPayment(String providerPaymentId) {
        Payment payment = new Payment(
                UUID.randomUUID(), new java.math.BigDecimal("10.00"), "EUR", "REF", providerPaymentId,
                Instant.now());
        return payments.saveAndFlush(payment);
    }
}
