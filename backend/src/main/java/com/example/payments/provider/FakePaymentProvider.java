package com.example.payments.provider;

import com.example.payments.service.PaymentCreatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
    private final ProviderCallbackClient callbackClient;
    private final ThreadPoolTaskScheduler scheduler;
    private final Clock clock;

    public FakePaymentProvider(ProviderProperties properties, ProviderCallbackClient callbackClient,
                               ThreadPoolTaskScheduler scheduler, Clock clock) {
        this.properties = properties;
        this.callbackClient = callbackClient;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public String authorize(BigDecimal amount, String currency) {
        return "prov-" + UUID.randomUUID();
    }

    /**
     * Runs only after the payment transaction commits, so the provider can never race ahead of
     * the row it is about to update.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCreated(PaymentCreatedEvent event) {
        if (!properties.isAutoCallback()) {
            return;
        }
        long delayMs = randomDelayMs();
        scheduler.schedule(
                () -> callbackClient.send(buildCallback(event.providerPaymentId())),
                Instant.now(clock).plusMillis(delayMs));
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
