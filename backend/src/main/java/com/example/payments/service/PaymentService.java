package com.example.payments.service;

import com.example.payments.api.dto.CreatePaymentRequest;
import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.provider.PaymentProvider;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentProvider provider,
                          ApplicationEventPublisher events, Clock clock) {
        this.payments = payments;
        this.provider = provider;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Payment create(CreatePaymentRequest request) {
        String currency = request.currency().toUpperCase(Locale.ROOT);
        String providerPaymentId = provider.authorize(request.amount(), currency);

        Payment payment = new Payment(
                UUID.randomUUID(),
                request.amount().setScale(2),
                currency,
                request.customerReference(),
                providerPaymentId,
                clock.instant());
        payments.save(payment);

        // Delivered after commit, so the fake provider can never call back for a payment
        // that has not been committed yet.
        events.publishEvent(new PaymentCreatedEvent(payment.getId(), providerPaymentId));
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
