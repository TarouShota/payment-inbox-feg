package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The optional future-skew guard is off by default. A structurally valid future timestamp is
 * therefore a normal callback: it applies and moves the watermark.
 */
@SpringBootTest(properties = "app.provider.auto-callback=false")
@AutoConfigureMockMvc
class CallbackFutureTimestampTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository payments;

    @BeforeEach
    void cleanDatabase() {
        payments.deleteAll();
    }

    @Test
    void futureTimestampAppliesWhenTheGuardIsDisabled() throws Exception {
        String providerPaymentId = "prov-future";
        payments.saveAndFlush(new Payment(
                UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "REF",
                providerPaymentId, Instant.now()));

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "evt-future-default");
        body.put("providerPaymentId", providerPaymentId);
        body.put("status", "CAPTURED");
        body.put("occurredAt", future.toString());

        MvcResult result = mockMvc.perform(post("/provider-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("APPLIED");
        Payment payment = payments.findByProviderPaymentId(providerPaymentId).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(payment.getLastCallbackOccurredAt()).isEqualTo(future.truncatedTo(ChronoUnit.MICROS));
    }
}
