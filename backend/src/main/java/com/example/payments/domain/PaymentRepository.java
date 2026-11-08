package com.example.payments.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Reads the payment and takes a row lock (SELECT ... FOR UPDATE) so concurrent callbacks
     * for the same payment are applied one at a time. The lock is held until the surrounding
     * transaction commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerPaymentId = :providerPaymentId")
    Optional<Payment> findByProviderPaymentIdForUpdate(@Param("providerPaymentId") String providerPaymentId);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
