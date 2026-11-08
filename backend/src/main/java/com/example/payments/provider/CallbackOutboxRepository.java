package com.example.payments.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallbackOutboxRepository extends JpaRepository<CallbackOutbox, UUID> {

    /**
     * Deliberately small and unordered only by due time. The relay is single-instance in this
     * demo; production would claim rows with FOR UPDATE SKIP LOCKED or an external queue.
     */
    List<CallbackOutbox> findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
            CallbackDeliveryState state, Instant now);

    long countByState(CallbackDeliveryState state);
}
