package com.example.payments.service;

import com.example.payments.api.dto.ProviderCallbackRequest;
import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProcessedCallback;
import com.example.payments.domain.ProcessedCallbackRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final PaymentRepository payments;
    private final ProcessedCallbackRepository processedCallbacks;
    private final CallbackProperties properties;
    private final Clock clock;

    public CallbackService(PaymentRepository payments,
                           ProcessedCallbackRepository processedCallbacks,
                           CallbackProperties properties,
                           Clock clock) {
        this.payments = payments;
        this.processedCallbacks = processedCallbacks;
        this.properties = properties;
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
        Instant now = clock.instant();
        Instant occurredAt = request.occurredAt().truncatedTo(ChronoUnit.MICROS);

        Payment payment = payments.findByProviderPaymentIdForUpdate(request.providerPaymentId())
                .orElseGet(() -> {
                    log.info("callback rejected reason=unknown_provider_payment eventId={} providerPaymentId={}",
                            request.eventId(), request.providerPaymentId());
                    throw new UnknownProviderPaymentException(request.providerPaymentId());
                });

        if (processedCallbacks.existsById(request.eventId())) {
            log.debug("callback outcome=DUPLICATE eventId={} paymentId={} reason=already_recorded",
                    request.eventId(), payment.getId());
            return CallbackApplyResult.DUPLICATE;
        }

        // Optional guard, off by default. When enabled, a timestamp far ahead of our clock
        // would otherwise advance the watermark and freeze the payment. The lookup above runs
        // first so an unknown provider id is still a 404, even with a future timestamp.
        Duration maxFutureSkew = properties.getMaxFutureSkew();
        if (!maxFutureSkew.isZero() && !maxFutureSkew.isNegative()
                && occurredAt.isAfter(now.plus(maxFutureSkew))) {
            log.warn("callback rejected reason=occurred_at_in_future eventId={} providerPaymentId={} occurredAt={}",
                    request.eventId(), request.providerPaymentId(), occurredAt);
            throw new InvalidCallbackTimeException();
        }

        // Record every accepted event id, including events that end up ignored or suppressed,
        // so rule 1 (exactly once per eventId) holds for the whole service. Persistable on the
        // entity makes this an INSERT, so a concurrent duplicate fails on the primary key.
        try {
            processedCallbacks.saveAndFlush(
                    new ProcessedCallback(request.eventId(), payment.getId(), now));
        } catch (DataIntegrityViolationException e) {
            log.debug("callback outcome=DUPLICATE eventId={} paymentId={} reason=insert_conflict",
                    request.eventId(), payment.getId());
            throw new DuplicateEventException(request.eventId(), e);
        }

        // Rule 2: only callbacks strictly newer than the last applied one may change the payment.
        if (payment.getLastCallbackOccurredAt() != null
                && !occurredAt.isAfter(payment.getLastCallbackOccurredAt())) {
            log.info("callback outcome=IGNORED eventId={} paymentId={} providerStatus={} occurredAt={}"
                            + " reason=not_newer_than_watermark",
                    request.eventId(), payment.getId(), request.status(), occurredAt);
            return CallbackApplyResult.IGNORED;
        }

        PaymentStatus mappedStatus = mapStatus(request.status());
        if (request.status() != null && mappedStatus == PaymentStatus.UNKNOWN) {
            log.warn("callback unknown_provider_status eventId={} paymentId={} providerStatus={}",
                    request.eventId(), payment.getId(), request.status());
        }

        // Rule 3, extended for terminal safety: a known outcome is never replaced by UNKNOWN,
        // and a late AUTHORIZED (PENDING) must not regress a terminal payment either.
        if (payment.getStatus().isTerminal()
                && (mappedStatus == PaymentStatus.UNKNOWN || mappedStatus == PaymentStatus.PENDING)) {
            log.info("callback outcome=SUPPRESSED eventId={} paymentId={} currentStatus={} mappedStatus={}"
                            + " providerStatus={}",
                    request.eventId(), payment.getId(), payment.getStatus(), mappedStatus, request.status());
            return CallbackApplyResult.SUPPRESSED;
        }

        PaymentStatus previousStatus = payment.getStatus();
        payment.applyCallback(mappedStatus, request.status(), occurredAt);
        log.info("callback outcome=APPLIED eventId={} paymentId={} from={} to={} providerStatus={}"
                        + " occurredAt={}",
                request.eventId(), payment.getId(), previousStatus, mappedStatus, request.status(), occurredAt);
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
