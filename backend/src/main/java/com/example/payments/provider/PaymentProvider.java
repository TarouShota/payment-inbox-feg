package com.example.payments.provider;

import java.math.BigDecimal;

public interface PaymentProvider {

    /**
     * Accepts the payment synchronously and returns the provider-side payment id. The outcome
     * is reported later, out of band, via a callback. A non-null idempotency key identifies
     * retries of the same request, so a real provider would return the same id for it.
     */
    String authorize(BigDecimal amount, String currency, String idempotencyKey);
}
