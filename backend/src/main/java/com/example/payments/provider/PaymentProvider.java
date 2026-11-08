package com.example.payments.provider;

import java.math.BigDecimal;

public interface PaymentProvider {

    /**
     * Accepts the payment synchronously and returns the provider-side payment id. The outcome
     * is reported later, out of band, via a callback.
     */
    String authorize(BigDecimal amount, String currency);
}
