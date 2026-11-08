package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.ProcessedCallbackRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
class PaymentApiTest {

    /**
     * Reads JSON numbers as BigDecimal without stripping trailing zeros (Jackson strips them
     * by default when building tree nodes, which would hide the API's 2-decimal money format).
     */
    private static final ObjectMapper EXACT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .build();

    @Autowired
    private MockMvc mockMvc;

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
    void createPaymentReturns201WithPendingStatusAndLocation() throws Exception {
        MvcResult result = mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 12.50, "currency": "eur", "customerReference": "ORDER-1001"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();

        JsonNode body = read(result);
        UUID id = UUID.fromString(body.path("id").asText());
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("providerStatus").isNull()).isTrue();
        assertThat(body.path("providerPaymentId").asText()).startsWith("prov-");
        assertThat(body.path("lastCallbackOccurredAt").isNull()).isTrue();
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(body.path("amount").asText()).isEqualTo("12.50");
        assertThat(body.path("currency").asText()).isEqualTo("EUR");
        assertThat(body.path("customerReference").asText()).isEqualTo("ORDER-1001");
        assertThat(result.getResponse().getHeader("Location")).endsWith("/payments/" + id);

        JsonNode fetched = read(mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(fetched).isEqualTo(body);

        List<String> fieldNames = new ArrayList<>();
        fetched.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "id", "status", "providerStatus", "providerPaymentId", "lastCallbackOccurredAt",
                "amount", "currency", "customerReference");
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(put("/payments"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("amount=12.50"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    }

    @Test
    void createPaymentAcceptsAmountAsNumericString() throws Exception {
        JsonNode body = read(mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "10.25", "currency": "USD", "customerReference": "REF"}
                                """))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("10.25"));
    }

    @Test
    void createPaymentNormalisesWholeAmountToTwoDecimals() throws Exception {
        JsonNode body = read(mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5, "currency": "USD", "customerReference": "REF"}
                                """))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(body.path("amount").asText()).isEqualTo("5.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"amount\": null, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": null, \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": \"EUR\", \"customerReference\": null}",
            "{\"amount\": 0, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": 0.00, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": -1, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.345, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": 123456789012345678, \"currency\": \"EUR\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": \"EU\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": \"EURO\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": \"E1R\", \"customerReference\": \"REF\"}",
            "{\"amount\": 12.50, \"currency\": \"EUR\", \"customerReference\": \"   \"}",
            "{\"amount\": 12.50, \"currency\": \"EUR\", \"customerReference\": \"\"}"
    })
    void createPaymentRejectsInvalidPayloads(String body) throws Exception {
        JsonNode error = read(mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(error.path("error").asText()).isEqualTo("validation_failed");
    }

    @Test
    void createPaymentRejectsMalformedJson() throws Exception {
        JsonNode error = read(mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": "))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(error.path("error").asText()).isEqualTo("malformed_request");
    }

    @Test
    void getPaymentReturns404ForUnknownId() throws Exception {
        JsonNode error = read(mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn());

        assertThat(error.path("error").asText()).isEqualTo("payment_not_found");
    }

    @Test
    void getPaymentReturns400ForMalformedId() throws Exception {
        JsonNode error = read(mockMvc.perform(get("/payments/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(error.path("error").asText()).isEqualTo("invalid_parameter");
    }

    private JsonNode read(MvcResult result) throws Exception {
        return EXACT_MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
