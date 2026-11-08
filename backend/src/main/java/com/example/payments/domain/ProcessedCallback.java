package com.example.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One row per provider event ever accepted by the service. The event id is the primary
 * key, so idempotency is enforced globally (across payments) and by the database itself.
 */
@Entity
@Table(name = "processed_callbacks")
public class ProcessedCallback implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 128)
    private String eventId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedCallback() {
        // For JPA.
    }

    public ProcessedCallback(String eventId, UUID paymentId, Instant receivedAt) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.receivedAt = receivedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
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
