package com.example.payments.service;

public class UnknownProviderPaymentException extends RuntimeException {

    public UnknownProviderPaymentException(String providerPaymentId) {
        super("Unknown providerPaymentId: " + providerPaymentId);
    }
}
