package com.example.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallbackRelayTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final CallbackOutboxRepository outbox = mock(CallbackOutboxRepository.class);
    private final ProviderCallbackClient client = mock(ProviderCallbackClient.class);
    private final ProviderProperties properties = new ProviderProperties();
    private final CallbackRelay relay = new CallbackRelay(
            outbox, client, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private CallbackOutbox dueRow() {
        ProviderCallback callback = new ProviderCallback(
                "evt-1", "prov-1", "CAPTURED", NOW.minusSeconds(1));
        return new CallbackOutbox(
                UUID.randomUUID(), callback, NOW.minusSeconds(2), NOW);
    }

    @Test
    void successfulDeliveryMarksRowSent() {
        CallbackOutbox row = dueRow();
        when(outbox.findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                CallbackDeliveryState.PENDING, NOW)).thenReturn(List.of(row));

        relay.deliverDue();

        assertThat(row.getState()).isEqualTo(CallbackDeliveryState.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
        verify(outbox).save(row);
    }

    @Test
    void failedDeliveryRetriesWithBackoff() {
        CallbackOutbox row = dueRow();
        when(outbox.findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                CallbackDeliveryState.PENDING, NOW)).thenReturn(List.of(row));
        doThrow(new CallbackDeliveryException("connection refused", new RuntimeException()))
                .when(client).send(any());

        relay.deliverDue();

        assertThat(row.getState()).isEqualTo(CallbackDeliveryState.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusMillis(500));
        assertThat(row.getLastError()).contains("connection refused");
    }

    @Test
    void failedDeliveryStopsRetryingAfterMaxAttempts() {
        properties.setMaxAttempts(3);
        CallbackOutbox row = dueRow();
        when(outbox.findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                CallbackDeliveryState.PENDING, NOW)).thenReturn(List.of(row));
        doThrow(new CallbackDeliveryException("provider down", new RuntimeException()))
                .when(client).send(any());

        relay.deliverDue();
        relay.deliverDue();
        relay.deliverDue();

        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getState()).isEqualTo(CallbackDeliveryState.DEAD);
    }

    @Test
    void backoffIsCappedAtMaxRetry() {
        properties.setInitialRetryMs(500);
        properties.setMaxRetryMs(750);
        CallbackOutbox row = dueRow();
        when(outbox.findTop10ByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                CallbackDeliveryState.PENDING, NOW)).thenReturn(List.of(row));
        doThrow(new CallbackDeliveryException("provider down", new RuntimeException()))
                .when(client).send(any());

        relay.deliverDue();
        relay.deliverDue();

        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusMillis(750));
    }
}
