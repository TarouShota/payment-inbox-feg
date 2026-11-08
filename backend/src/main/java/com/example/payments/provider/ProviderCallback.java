package com.example.payments.provider;

import java.time.Instant;

/** The payload the fake provider posts to POST /provider-callbacks. */
public record ProviderCallback(
        String eventId,
        String providerPaymentId,
        String status,
        Instant occurredAt) {
}
