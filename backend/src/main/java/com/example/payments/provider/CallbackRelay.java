package com.example.payments.provider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers due outbox rows. One relay instance is enough for this demo; the callback endpoint's
 * eventId dedupe makes at-least-once delivery safe.
 */
@Component
public class CallbackRelay {

    private static final Logger log = LoggerFactory.getLogger(CallbackRelay.class);

    private final CallbackOutboxRepository outbox;
    private final ProviderCallbackClient callbackClient;
    private final ProviderProperties properties;
    private final Clock clock;

    public CallbackRelay(CallbackOutboxRepository outbox, ProviderCallbackClient callbackClient,
                         ProviderProperties properties, Clock clock) {
        this.outbox = outbox;
        this.callbackClient = callbackClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.provider.relay-interval-ms:100}")
    public void deliverDue() {
        List<CallbackOutbox> due = outbox
                .findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                        CallbackDeliveryState.PENDING, clock.instant());

        for (CallbackOutbox item : due) {
            try {
                callbackClient.send(item.toCallback());
                item.markSent(clock.instant());
                log.info("callback outbox delivered outboxId={} eventId={} attempts={}",
                        item.getId(), item.getEventId(), item.getAttempts() + 1);
            } catch (RuntimeException e) {
                item.markFailed(clock.instant(), e.getMessage(),
                        properties.getMaxAttempts(),
                        properties.getInitialRetryMs(),
                        properties.getMaxRetryMs());
                log.warn("callback outbox attempt failed outboxId={} eventId={} attempts={} state={} error={}",
                        item.getId(), item.getEventId(), item.getAttempts(), item.getState(), e.toString());
            }
            try {
                outbox.save(item);
            } catch (RuntimeException e) {
                // A transient DB error on one row must not skip the rest of this batch.
                log.error("callback outbox save failed outboxId={} eventId={} state={}",
                        item.getId(), item.getEventId(), item.getState(), e);
            }
        }
    }
}
