package com.example.payments.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CallbackSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final String BODY = "{\"eventId\":\"evt-1\"}";

    @Test
    void signsAndVerifiesTheSameBody() {
        CallbackSigner signer = signer(true, "test-only-signing-key");

        String timestamp = Long.toString(NOW.getEpochSecond());
        String signature = signer.sign(NOW.getEpochSecond(), BODY);

        assertThat(signature).matches("[0-9a-f]{64}");
        assertThat(signer.verify(timestamp, signature, BODY, NOW)).isTrue();
        assertThat(signer.verify(timestamp, signature, BODY + " ", NOW)).isFalse();
        assertThat(signer.verify(timestamp, "0".repeat(64), BODY, NOW)).isFalse();
    }

    @Test
    void rejectsTimestampsOutsideTheWindowInEitherDirection() {
        CallbackSigner signer = signer(true, "test-only-signing-key");
        long stale = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();
        long future = NOW.plus(Duration.ofMinutes(6)).getEpochSecond();

        assertThat(signer.verify(Long.toString(stale), signer.sign(stale, BODY), BODY, NOW)).isFalse();
        assertThat(signer.verify(Long.toString(future), signer.sign(future, BODY), BODY, NOW)).isFalse();
    }

    @Test
    void refusesToStartEnabledWithoutASecret() {
        assertThatThrownBy(() -> signer(true, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret");
    }

    private CallbackSigner signer(boolean enabled, String secret) {
        CallbackAuthProperties properties = new CallbackAuthProperties();
        properties.setEnabled(enabled);
        properties.setSecret(secret);
        properties.setMaxAge(Duration.ofMinutes(5));
        return new CallbackSigner(properties);
    }
}
