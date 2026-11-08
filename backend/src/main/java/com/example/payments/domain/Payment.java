package com.example.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "customer_reference", nullable = false, length = 255)
    private String customerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "provider_payment_id", nullable = false, length = 128, unique = true)
    private String providerPaymentId;

    @Column(name = "provider_status", length = 255)
    private String providerStatus;

    @Column(name = "last_callback_occurred_at")
    private Instant lastCallbackOccurredAt;

    /** Client-supplied key that makes POST /payments safe to retry. Null when not supplied. */
    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    /** SHA-256 of the normalized create request, used to reject key reuse with other data. */
    @Column(name = "idempotency_request_hash", length = 64)
    private String idempotencyRequestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
        // For JPA.
    }

    public Payment(UUID id, BigDecimal amount, String currency, String customerReference,
                   String providerPaymentId, Instant createdAt) {
        this(id, amount, currency, customerReference, providerPaymentId, createdAt, null, null);
    }

    public Payment(UUID id, BigDecimal amount, String currency, String customerReference,
                   String providerPaymentId, Instant createdAt, String idempotencyKey,
                   String idempotencyRequestHash) {
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.customerReference = customerReference;
        this.providerPaymentId = providerPaymentId;
        this.status = PaymentStatus.PENDING;
        this.createdAt = createdAt;
        this.idempotencyKey = idempotencyKey;
        this.idempotencyRequestHash = idempotencyRequestHash;
    }

    /**
     * Applies an accepted provider callback. The caller decides whether the callback is
     * allowed to change this payment; this method only records the applied state.
     */
    public void applyCallback(PaymentStatus newStatus, String rawProviderStatus, Instant occurredAt) {
        this.status = newStatus;
        this.providerStatus = rawProviderStatus;
        this.lastCallbackOccurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Instant getLastCallbackOccurredAt() {
        return lastCallbackOccurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getIdempotencyRequestHash() {
        return idempotencyRequestHash;
    }
}
