package com.example.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per provider event ever accepted by the service. The event id is the primary
 * key, so idempotency is enforced globally (across payments) and by the database itself.
 */
@Entity
@Table(name = "processed_callbacks")
public class ProcessedCallback {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 128)
    private String eventId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ProcessedCallback() {
        // For JPA.
    }

    public ProcessedCallback(String eventId, UUID paymentId, Instant receivedAt) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
