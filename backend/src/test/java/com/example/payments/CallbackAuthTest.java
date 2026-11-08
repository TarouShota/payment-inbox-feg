package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.ProcessedCallbackRepository;
import com.example.payments.security.CallbackSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * With callback auth enabled, only a correctly signed, fresh request is accepted. The test
 * secret is a fixture, not a real credential.
 */
@SpringBootTest(properties = {
        "app.provider.auto-callback=false",
        "app.callback.auth.enabled=true",
        "app.callback.auth.secret=test-only-signing-key"
})
@AutoConfigureMockMvc
class CallbackAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private ProcessedCallbackRepository processedCallbacks;

    @Autowired
    private CallbackSigner signer;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        processedCallbacks.deleteAll();
        payments.deleteAll();
    }

    @Test
    void validSignatureAppliesTheCallback() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));
        long timestamp = clock.instant().getEpochSecond();

        MvcResult result = mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(timestamp, body)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("result").asText()).isEqualTo("APPLIED");

        Payment payment = payment("prov-1");
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(processedCallbacks.count()).isEqualTo(1);
    }

    @Test
    void missingSignatureIsRejectedAndTheEventIsNotRecorded() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401))
                .andExpect(result -> assertThat(
                        result.getResponse().getContentAsString()).contains("invalid_signature"));

        assertThat(processedCallbacks.count()).isZero();
        assertThat(payment("prov-1").getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void badSignatureIsRejected() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));
        long timestamp = clock.instant().getEpochSecond();

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, "0".repeat(64)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(processedCallbacks.count()).isZero();
    }

    @Test
    void tamperingWithTheBodyInvalidatesTheSignature() throws Exception {
        createPayment("prov-1");
        String signedBody = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));
        String tamperedBody = signedBody.replace("CAPTURED", "DECLINED");
        long timestamp = clock.instant().getEpochSecond();

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody)
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(timestamp, signedBody)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(payment("prov-1").getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void staleSignatureIsRejectedEvenWhenTheHmacIsValid() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));
        long staleTimestamp = clock.instant().minus(Duration.ofMinutes(10)).getEpochSecond();

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(staleTimestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(staleTimestamp, body)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(processedCallbacks.count()).isZero();
    }

    @Test
    void pathParametersDoNotBypassAuthentication() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));

        mockMvc.perform(post("/provider-callbacks;probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        assertThat(processedCallbacks.count()).isZero();
    }

    @Test
    void aRejectedCallbackDoesNotBlockTheSameEventIdLater() throws Exception {
        createPayment("prov-1");
        String body = callbackJson("evt-1", "prov-1", "CAPTURED", Instant.now().minusSeconds(60));

        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        long timestamp = clock.instant().getEpochSecond();
        mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(timestamp, body)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        assertThat(payment("prov-1").getStatus().name()).isEqualTo("SUCCEEDED");
    }

    private void createPayment(String providerPaymentId) {
        Payment payment = new Payment(
                UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "REF", providerPaymentId, Instant.now());
        payments.saveAndFlush(payment);
    }

    private Payment payment(String providerPaymentId) {
        return payments.findByProviderPaymentId(providerPaymentId).orElseThrow();
    }

    private String callbackJson(String eventId, String providerPaymentId, String status,
                                Instant occurredAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("providerPaymentId", providerPaymentId);
        body.put("occurredAt", occurredAt.toString());
        body.put("status", status);
        return objectMapper.writeValueAsString(body);
    }
}
