package com.example.payments.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A callback that must be delivered. Written in the same transaction as the payment, then
 * picked up by {@link CallbackRelay}. The row is the outbox, the retry state and the dead-letter
 * record in one place.
 */
@Entity
@Table(name = "callback_outbox")
public class CallbackOutbox {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false, length = 128)
    private String eventId;

    @Column(name = "provider_payment_id", nullable = false, updatable = false, length = 128)
    private String providerPaymentId;

    @Column(name = "provider_status", length = 255)
    private String providerStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private CallbackDeliveryState state;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected CallbackOutbox() {
        // For JPA.
    }

    public CallbackOutbox(UUID paymentId, ProviderCallback callback, Instant createdAt, Instant dueAt) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.eventId = callback.eventId();
        this.providerPaymentId = callback.providerPaymentId();
        this.providerStatus = callback.status();
        this.occurredAt = callback.occurredAt();
        this.createdAt = createdAt;
        this.nextAttemptAt = dueAt;
        this.attempts = 0;
        this.state = CallbackDeliveryState.PENDING;
    }

    public ProviderCallback toCallback() {
        return new ProviderCallback(eventId, providerPaymentId, providerStatus, occurredAt);
    }

    public void markSent(Instant now) {
        this.state = CallbackDeliveryState.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markFailed(Instant now, String error, int maxAttempts,
                           long initialRetryMs, long maxRetryMs) {
        this.attempts += 1;
        this.lastError = truncate(error);
        if (this.attempts >= maxAttempts) {
            this.state = CallbackDeliveryState.DEAD;
            this.nextAttemptAt = now;
            return;
        }
        this.state = CallbackDeliveryState.PENDING;
        this.nextAttemptAt = now.plusMillis(backoffMs(initialRetryMs, maxRetryMs));
    }

    private long backoffMs(long initialRetryMs, long maxRetryMs) {
        long initial = Math.max(1, initialRetryMs);
        long max = Math.max(initial, maxRetryMs);
        int exponent = Math.min(this.attempts - 1, 20);
        long delay = initial * (1L << exponent);
        return Math.min(max, delay);
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "delivery failed";
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public CallbackDeliveryState getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
