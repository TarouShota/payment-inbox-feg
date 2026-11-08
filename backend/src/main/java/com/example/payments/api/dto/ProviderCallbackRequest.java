package com.example.payments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ProviderCallbackRequest(
        @NotBlank @Size(max = 128)
        String eventId,

        @NotBlank @Size(max = 128)
        String providerPaymentId,

        @NotNull
        Instant occurredAt,

        @Size(max = 255)
        String status) {
}
