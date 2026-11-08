package com.example.payments.provider;

import com.example.payments.service.PaymentCreatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stand-in for an external card provider. It accepts the payment immediately (returning an
 * id) and reports the configured outcome later through its own HTTP callback.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    private final ProviderProperties properties;
    private final CallbackOutboxRepository outbox;
    private final Clock clock;

    public FakePaymentProvider(ProviderProperties properties, CallbackOutboxRepository outbox,
                               Clock clock) {
        this.properties = properties;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public String authorize(BigDecimal amount, String currency) {
        return "prov-" + UUID.randomUUID();
    }

    /**
     * Writes the callback into the outbox in the same transaction as the payment. Either both
     * rows commit or neither does, so a committed payment can never lose its callback. The
     * relay delivers it later.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentCreated(PaymentCreatedEvent event) {
        if (!properties.isAutoCallback()) {
            return;
        }
        Instant now = Instant.now(clock);
        ProviderCallback callback = buildCallback(event.providerPaymentId());
        outbox.save(new CallbackOutbox(
                event.paymentId(), callback, now, now.plusMillis(randomDelayMs())));
    }

    ProviderCallback buildCallback(String providerPaymentId) {
        return new ProviderCallback(
                "evt-" + UUID.randomUUID(),
                providerPaymentId,
                properties.getOutcome(),
                Instant.now(clock));
    }

    private long randomDelayMs() {
        long min = Math.max(0, properties.getMinDelayMs());
        long max = Math.max(min, properties.getMaxDelayMs());
        return max == min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
