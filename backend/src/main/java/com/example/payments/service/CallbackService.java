package com.example.payments.service;

import com.example.payments.api.dto.ProviderCallbackRequest;
import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProcessedCallback;
import com.example.payments.domain.ProcessedCallbackRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallbackService {

    private final PaymentRepository payments;
    private final ProcessedCallbackRepository processedCallbacks;
    private final Clock clock;

    public CallbackService(PaymentRepository payments,
                           ProcessedCallbackRepository processedCallbacks,
                           Clock clock) {
        this.payments = payments;
        this.processedCallbacks = processedCallbacks;
        this.clock = clock;
    }

    /**
     * Applies one provider callback in a single transaction. The payment row is locked for
     * the whole read-check-write sequence, so the ordering and terminal-state rules always
     * see a consistent state.
     *
     * <p>Order matters: the provider payment must be known before the event is treated as a
     * duplicate, so an unknown providerPaymentId is always a 404 and is never recorded.
     */
    @Transactional
    public CallbackApplyResult handle(ProviderCallbackRequest request) {
        Payment payment = payments.findByProviderPaymentIdForUpdate(request.providerPaymentId())
                .orElseThrow(() -> new UnknownProviderPaymentException(request.providerPaymentId()));

        if (processedCallbacks.existsById(request.eventId())) {
            return CallbackApplyResult.DUPLICATE;
        }

        // Record every accepted event id, including events that end up ignored or suppressed,
        // so rule 1 (exactly once per eventId) holds for the whole service.
        Instant occurredAt = request.occurredAt().truncatedTo(ChronoUnit.MICROS);
        processedCallbacks.saveAndFlush(new ProcessedCallback(request.eventId(), payment.getId(), clock.instant()));

        // Rule 2: only callbacks strictly newer than the last applied one may change the payment.
        if (payment.getLastCallbackOccurredAt() != null
                && !occurredAt.isAfter(payment.getLastCallbackOccurredAt())) {
            return CallbackApplyResult.IGNORED;
        }

        PaymentStatus mappedStatus = mapStatus(request.status());

        // Rule 3, extended for terminal safety: a known outcome is never replaced by UNKNOWN,
        // and a late AUTHORIZED (PENDING) must not regress a terminal payment either.
        if (payment.getStatus().isTerminal()
                && (mappedStatus == PaymentStatus.UNKNOWN || mappedStatus == PaymentStatus.PENDING)) {
            return CallbackApplyResult.SUPPRESSED;
        }

        payment.applyCallback(mappedStatus, request.status(), occurredAt);
        return CallbackApplyResult.APPLIED;
    }

    private static PaymentStatus mapStatus(String providerStatus) {
        if (providerStatus == null) {
            return PaymentStatus.UNKNOWN;
        }
        return switch (providerStatus) {
            case "AUTHORIZED" -> PaymentStatus.PENDING;
            case "CAPTURED" -> PaymentStatus.SUCCEEDED;
            case "DECLINED" -> PaymentStatus.FAILED;
            default -> PaymentStatus.UNKNOWN;
        };
    }
}
