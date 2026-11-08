package com.example.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class FakeProviderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private FakePaymentProvider providerWithOutcome(String outcome) {
        ProviderProperties properties = new ProviderProperties();
        properties.setOutcome(outcome);
        return new FakePaymentProvider(
                properties,
                mock(ProviderCallbackClient.class),
                mock(ThreadPoolTaskScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authorizeReturnsProviderPaymentIdImmediately() {
        FakePaymentProvider provider = providerWithOutcome("CAPTURED");

        String id = provider.authorize(new BigDecimal("10.00"), "EUR");

        assertThat(id).startsWith("prov-");
        assertThat(provider.authorize(new BigDecimal("10.00"), "EUR")).isNotEqualTo(id);
    }

    @Test
    void buildsCallbackWithConfiguredOutcome() {
        ProviderCallback callback = providerWithOutcome("CAPTURED").buildCallback("prov-42");

        assertThat(callback.eventId()).startsWith("evt-");
        assertThat(callback.providerPaymentId()).isEqualTo("prov-42");
        assertThat(callback.status()).isEqualTo("CAPTURED");
        assertThat(callback.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void outcomeIsConfigurableToDeclinedErrorOrAnythingElse() {
        assertThat(providerWithOutcome("DECLINED").buildCallback("prov-1").status()).isEqualTo("DECLINED");
        assertThat(providerWithOutcome("ERROR").buildCallback("prov-1").status()).isEqualTo("ERROR");
        assertThat(providerWithOutcome("SOMETHING_ELSE").buildCallback("prov-1").status())
                .isEqualTo("SOMETHING_ELSE");
    }

    @Test
    void eachCallbackGetsAFreshEventId() {
        FakePaymentProvider provider = providerWithOutcome("CAPTURED");

        assertThat(provider.buildCallback("prov-1").eventId())
                .isNotEqualTo(provider.buildCallback("prov-1").eventId());
    }
}
