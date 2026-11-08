package com.example.payments.service;

import java.util.UUID;

public record PaymentCreatedEvent(UUID paymentId, String providerPaymentId) {
}
