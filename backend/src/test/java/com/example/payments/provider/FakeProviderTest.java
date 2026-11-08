package com.example.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.payments.service.PaymentCreatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FakeProviderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private FakePaymentProvider providerWithOutcome(String outcome,
                                                    CallbackOutboxRepository outbox) {
        ProviderProperties properties = new ProviderProperties();
        properties.setOutcome(outcome);
        properties.setMinDelayMs(60);
        properties.setMaxDelayMs(60);
        return new FakePaymentProvider(
                properties, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authorizeReturnsProviderPaymentIdImmediately() {
        FakePaymentProvider provider = providerWithOutcome("CAPTURED", mock(CallbackOutboxRepository.class));

        String id = provider.authorize(new BigDecimal("10.00"), "EUR", null);

        assertThat(id).startsWith("prov-");
        assertThat(provider.authorize(new BigDecimal("10.00"), "EUR", null)).isNotEqualTo(id);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameProviderId() {
        FakePaymentProvider provider = providerWithOutcome("CAPTURED", mock(CallbackOutboxRepository.class));

        assertThat(provider.authorize(new BigDecimal("10.00"), "EUR", "checkout-1"))
                .isEqualTo(provider.authorize(new BigDecimal("10.00"), "EUR", "checkout-1"))
                .startsWith("prov-");
        assertThat(provider.authorize(new BigDecimal("10.00"), "EUR", "checkout-2"))
                .isNotEqualTo(provider.authorize(new BigDecimal("10.00"), "EUR", "checkout-1"));
    }

    @Test
    void buildsCallbackWithConfiguredOutcome() {
        ProviderCallback callback = providerWithOutcome("CAPTURED", mock(CallbackOutboxRepository.class))
                .buildCallback("prov-42");

        assertThat(callback.eventId()).startsWith("evt-");
        assertThat(callback.providerPaymentId()).isEqualTo("prov-42");
        assertThat(callback.status()).isEqualTo("CAPTURED");
        assertThat(callback.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void outcomeIsConfigurableToDeclinedErrorOrAnythingElse() {
        CallbackOutboxRepository outbox = mock(CallbackOutboxRepository.class);
        assertThat(providerWithOutcome("DECLINED", outbox).buildCallback("prov-1").status())
                .isEqualTo("DECLINED");
        assertThat(providerWithOutcome("ERROR", outbox).buildCallback("prov-1").status())
                .isEqualTo("ERROR");
        assertThat(providerWithOutcome("SOMETHING_ELSE", outbox).buildCallback("prov-1").status())
                .isEqualTo("SOMETHING_ELSE");
    }

    @Test
    void eachCallbackGetsAFreshEventId() {
        FakePaymentProvider provider = providerWithOutcome("CAPTURED", mock(CallbackOutboxRepository.class));

        assertThat(provider.buildCallback("prov-1").eventId())
                .isNotEqualTo(provider.buildCallback("prov-1").eventId());
    }

    @Test
    void paymentCreatedEventEnqueuesOneOutboxRow() {
        CallbackOutboxRepository outbox = mock(CallbackOutboxRepository.class);
        FakePaymentProvider provider = providerWithOutcome("CAPTURED", outbox);
        UUID paymentId = UUID.randomUUID();

        provider.onPaymentCreated(new PaymentCreatedEvent(paymentId, "prov-42"));

        ArgumentCaptor<CallbackOutbox> saved = ArgumentCaptor.forClass(CallbackOutbox.class);
        verify(outbox).save(saved.capture());
        CallbackOutbox row = saved.getValue();
        assertThat(row.getPaymentId()).isEqualTo(paymentId);
        assertThat(row.getProviderPaymentId()).isEqualTo("prov-42");
        assertThat(row.getProviderStatus()).isEqualTo("CAPTURED");
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusMillis(60));
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getState()).isEqualTo(CallbackDeliveryState.PENDING);
    }

    @Test
    void autoCallbackDisabledEnqueuesNothing() {
        CallbackOutboxRepository outbox = mock(CallbackOutboxRepository.class);
        ProviderProperties properties = new ProviderProperties();
        properties.setAutoCallback(false);
        FakePaymentProvider provider = new FakePaymentProvider(
                properties, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

        provider.onPaymentCreated(new PaymentCreatedEvent(UUID.randomUUID(), "prov-42"));

        verify(outbox, never()).save(any());
    }
}
