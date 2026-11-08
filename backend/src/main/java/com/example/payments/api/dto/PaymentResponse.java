package com.example.payments.api.dto;

import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        String providerStatus,
        String providerPaymentId,
        Instant lastCallbackOccurredAt,
        BigDecimal amount,
        String currency,
        String customerReference) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getProviderStatus(),
                payment.getProviderPaymentId(),
                payment.getLastCallbackOccurredAt(),
                payment.getAmount().setScale(2),
                payment.getCurrency(),
                payment.getCustomerReference());
    }
}
