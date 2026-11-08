package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.ProcessedCallbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.provider.auto-callback=false")
@AutoConfigureMockMvc
class ProviderCallbackRulesTest {

    private static final Instant T10 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T15 = Instant.parse("2026-01-01T10:15:00Z");
    private static final Instant T20 = Instant.parse("2026-01-01T10:20:00Z");

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

    // ---------------------------------------------------------------- rule 1: eventId once

    @Test
    void replayOfIdenticalEventIsDuplicateAndChangesNothing() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        assertThat(callback("evt-1", providerPaymentId, "CAPTURED", T10).path("result").asText())
                .isEqualTo("APPLIED");
        assertThat(callback("evt-1", providerPaymentId, "CAPTURED", T10).path("result").asText())
                .isEqualTo("DUPLICATE");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
        assertThat(processedCallbacks.count()).isEqualTo(1);
    }

    @Test
    void replayOfSameEventWithDifferentPayloadIsDuplicate() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-1", providerPaymentId, "DECLINED", T20).path("result").asText())
                .isEqualTo("DUPLICATE");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void eventIdIsDeduplicatedAcrossPayments() throws Exception {
        String first = createPayment("prov-1");
        String second = createPayment("prov-2");
        callback("evt-1", first, "CAPTURED", T10);

        assertThat(callback("evt-1", second, "CAPTURED", T10).path("result").asText())
                .isEqualTo("DUPLICATE");

        Payment untouched = payment("prov-2");
        assertThat(untouched.getStatus().name()).isEqualTo("PENDING");
        assertThat(untouched.getProviderStatus()).isNull();
        assertThat(untouched.getLastCallbackOccurredAt()).isNull();
    }

    // ---------------------------------------------------------------- rule 2: occurredAt order

    @Test
    void olderCallbackIsIgnored() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-2", providerPaymentId, "DECLINED", T10.minusSeconds(1))
                .path("result").asText()).isEqualTo("IGNORED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void callbackWithEqualTimestampIsIgnored() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-2", providerPaymentId, "DECLINED", T10).path("result").asText())
                .isEqualTo("IGNORED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void equalInstantExpressedWithDifferentOffsetIsIgnored() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", "2026-01-01T10:00:00Z");

        // 12:00+02:00 is the same instant as 10:00Z, just written differently.
        assertThat(callback("evt-2", providerPaymentId, "DECLINED", "2026-01-01T12:00:00+02:00")
                .path("result").asText()).isEqualTo("IGNORED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void staleCallbackDoesNotMoveTheWatermark() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);
        callback("evt-2", providerPaymentId, "DECLINED", T10.minusSeconds(30));

        assertThat(payment("prov-1").getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    // ---------------------------------------------------------------- rule 3: terminal protection

    @Test
    void unknownDoesNotReplaceSucceeded() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-2", providerPaymentId, "ERROR", T20).path("result").asText())
                .isEqualTo("SUPPRESSED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void unknownDoesNotReplaceFailed() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "DECLINED", T10);

        assertThat(callback("evt-2", providerPaymentId, "UNKNOWN_VALUE", T20).path("result").asText())
                .isEqualTo("SUPPRESSED");
        assertThat(callback("evt-3", providerPaymentId, null, T20).path("result").asText())
                .isEqualTo("SUPPRESSED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");
        assertThat(payment.getProviderStatus()).isEqualTo("DECLINED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void lateAuthorizationDoesNotRegressATerminalPayment() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-2", providerPaymentId, "AUTHORIZED", T20).path("result").asText())
                .isEqualTo("SUPPRESSED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void newerDeclinedReplacesSucceeded() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        assertThat(callback("evt-2", providerPaymentId, "DECLINED", T20).path("result").asText())
                .isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");
        assertThat(payment.getProviderStatus()).isEqualTo("DECLINED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T20);
    }

    @Test
    void newerCapturedReplacesFailed() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "DECLINED", T10);

        assertThat(callback("evt-2", providerPaymentId, "CAPTURED", T20).path("result").asText())
                .isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T20);
    }

    @Test
    void unknownAppliesWhilePaymentIsPending() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        assertThat(callback("evt-1", providerPaymentId, "ERROR", T10).path("result").asText())
                .isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("UNKNOWN");
        assertThat(payment.getProviderStatus()).isEqualTo("ERROR");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    @Test
    void unknownAndPendingStatesCanStillBecomeKnownOutcomes() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "WHATEVER", T10);
        callback("evt-2", providerPaymentId, "AUTHORIZED", T15);

        Payment afterAuthorized = payment("prov-1");
        assertThat(afterAuthorized.getStatus().name()).isEqualTo("PENDING");
        assertThat(afterAuthorized.getProviderStatus()).isEqualTo("AUTHORIZED");

        callback("evt-3", providerPaymentId, "CAPTURED", T20);
        assertThat(payment("prov-1").getStatus().name()).isEqualTo("SUCCEEDED");
    }

    // ---------------------------------------------------------------- decision proof

    @Test
    void suppressedCallbackDoesNotAdvanceTheWatermarkSoALaterIntermediateOutcomeStillApplies() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        assertThat(callback("evt-1", providerPaymentId, "CAPTURED", T10).path("result").asText())
                .isEqualTo("APPLIED");
        assertThat(callback("evt-2", providerPaymentId, "ERROR", T20).path("result").asText())
                .isEqualTo("SUPPRESSED");

        // Had the suppressed UNKNOWN moved lastCallbackOccurredAt to T20, this T15 decline
        // would be rejected as stale and the real outcome would be lost.
        assertThat(callback("evt-3", providerPaymentId, "DECLINED", T15).path("result").asText())
                .isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");
        assertThat(payment.getProviderStatus()).isEqualTo("DECLINED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T15);
    }

    @Test
    void suppressedEventIsStillRecordedForIdempotency() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);
        callback("evt-2", providerPaymentId, "ERROR", T20);

        assertThat(callback("evt-2", providerPaymentId, "ERROR", T20).path("result").asText())
                .isEqualTo("DUPLICATE");
        assertThat(payment("prov-1").getStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void staleEventIsRecordedAndReplayIsDuplicateNotIgnored() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);
        assertThat(callback("evt-2", providerPaymentId, "DECLINED", T10.minusSeconds(5))
                .path("result").asText()).isEqualTo("IGNORED");

        assertThat(callback("evt-2", providerPaymentId, "DECLINED", T10.minusSeconds(5))
                .path("result").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void suppressedAndStaleEventsDoNotChangeThePayment() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);
        JsonNode before = read(mockMvc.perform(get("/payments/" + idFor(providerPaymentId)))
                .andExpect(status().isOk()).andReturn());

        callback("evt-2", providerPaymentId, "ERROR", T20);          // suppressed
        callback("evt-3", providerPaymentId, "DECLINED", T10);       // stale (equal)

        JsonNode after = read(mockMvc.perform(get("/payments/" + idFor(providerPaymentId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(after).isEqualTo(before);
    }

    // ---------------------------------------------------------------- status mapping

    @ParameterizedTest
    @CsvSource({
            "AUTHORIZED, PENDING",
            "CAPTURED, SUCCEEDED",
            "DECLINED, FAILED",
            "ERROR, UNKNOWN",
            "captured, UNKNOWN",
            "SOMETHING_NEW, UNKNOWN"
    })
    void mapsProviderStatuses(String providerStatus, String expectedStatus) throws Exception {
        String providerPaymentId = createPayment("prov-1");

        callback("evt-1", providerPaymentId, providerStatus, T10);

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo(expectedStatus);
        assertThat(payment.getProviderStatus()).isEqualTo(providerStatus);
    }

    @Test
    void nullProviderStatusMapsToUnknown() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        assertThat(callback("evt-1", providerPaymentId, null, T10).path("result").asText())
                .isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("UNKNOWN");
        assertThat(payment.getProviderStatus()).isNull();
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(T10);
    }

    // ---------------------------------------------------------------- precedence + validation

    @Test
    void unknownProviderPaymentIs404AndIsNotRecordedAsProcessed() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        MvcResult rejected = mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson("evt-1", "prov-does-not-exist", "CAPTURED", T10)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(read(rejected).path("error").asText()).isEqualTo("unknown_provider_payment");

        // Because the 404 path records nothing, the same eventId can still be applied later.
        assertThat(callback("evt-1", providerPaymentId, "CAPTURED", T10).path("result").asText())
                .isEqualTo("APPLIED");
        assertThat(payment("prov-1").getStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void duplicateEventIdWithUnknownProviderPaymentIsStill404() throws Exception {
        String providerPaymentId = createPayment("prov-1");
        callback("evt-1", providerPaymentId, "CAPTURED", T10);

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson("evt-1", "prov-does-not-exist", "CAPTURED", T20)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nullStatusIsAccepted() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson("evt-1", providerPaymentId, null, T10)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"providerPaymentId\": \"prov-1\", \"occurredAt\": \"2026-01-01T10:00:00Z\", \"status\": \"CAPTURED\"}",
            "{\"eventId\": \"\", \"providerPaymentId\": \"prov-1\", \"occurredAt\": \"2026-01-01T10:00:00Z\"}",
            "{\"eventId\": \"   \", \"providerPaymentId\": \"prov-1\", \"occurredAt\": \"2026-01-01T10:00:00Z\"}",
            "{\"eventId\": \"evt-1\", \"occurredAt\": \"2026-01-01T10:00:00Z\"}",
            "{\"eventId\": \"evt-1\", \"providerPaymentId\": \"\", \"occurredAt\": \"2026-01-01T10:00:00Z\"}",
            "{\"eventId\": \"evt-1\", \"providerPaymentId\": \"prov-1\"}",
            "{\"eventId\": \"evt-1\", \"providerPaymentId\": \"prov-1\", \"occurredAt\": \"not-a-time\"}",
            "{\"eventId\": \"evt-1\", \"providerPaymentId\": \"prov-1\", \"occurredAt\": \"2026-01-01T10:00:00\"}",
            "{\"eventId\": \"evt-1\", \"providerPaymentId\": \"prov-1\", \"occurredAt\": null}"
    })
    void rejectsInvalidCallbackPayloads(String body) throws Exception {
        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedEventIdAndStatus() throws Exception {
        String providerPaymentId = createPayment("prov-1");

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson("e".repeat(129), providerPaymentId, "CAPTURED", T10)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson("evt-1", providerPaymentId, "X".repeat(256), T10)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private String createPayment(String providerPaymentId) {
        Payment payment = new Payment(
                UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "REF", providerPaymentId, Instant.now());
        payments.saveAndFlush(payment);
        return providerPaymentId;
    }

    private JsonNode callback(String eventId, String providerPaymentId, String status, Instant occurredAt)
            throws Exception {
        return callback(eventId, providerPaymentId, status, occurredAt.toString());
    }

    private JsonNode callback(String eventId, String providerPaymentId, String status, String occurredAt)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackJson(eventId, providerPaymentId, status, occurredAt)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private String callbackJson(String eventId, String providerPaymentId, String status, Instant occurredAt)
            throws Exception {
        return callbackJson(eventId, providerPaymentId, status, occurredAt.toString());
    }

    private String callbackJson(String eventId, String providerPaymentId, String status, String occurredAt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("providerPaymentId", providerPaymentId);
        body.put("occurredAt", occurredAt);
        body.put("status", status);
        return objectMapper.writeValueAsString(body);
    }

    private Payment payment(String providerPaymentId) {
        return payments.findByProviderPaymentId(providerPaymentId).orElseThrow();
    }

    private UUID idFor(String providerPaymentId) {
        return payment(providerPaymentId).getId();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
