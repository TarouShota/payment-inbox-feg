package com.example.payments.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.callback")
public class CallbackProperties {

    /**
     * Optional guard: how far ahead of the server clock a provider timestamp may be before it
     * is rejected. Zero (the default) disables the guard, so a structurally valid future
     * timestamp follows the normal ordering rules.
     */
    private Duration maxFutureSkew = Duration.ZERO;

    public Duration getMaxFutureSkew() {
        return maxFutureSkew;
    }

    public void setMaxFutureSkew(Duration maxFutureSkew) {
        this.maxFutureSkew = maxFutureSkew;
    }
}
