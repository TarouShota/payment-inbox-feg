package com.example.payments.service;

import com.example.payments.domain.Payment;

public record CreatePaymentResult(Payment payment, boolean replayed) {
}
