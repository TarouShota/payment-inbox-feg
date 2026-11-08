package com.example.payments.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code")
        String currency,

        @NotBlank @Size(max = 255)
        String customerReference) {
}
