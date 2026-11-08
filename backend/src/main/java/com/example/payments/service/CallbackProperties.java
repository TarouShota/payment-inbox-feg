package com.example.payments.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.callback")
public class CallbackProperties {

    /** How far ahead of the server clock a provider timestamp may be before it is rejected. */
    private Duration maxFutureSkew = Duration.ofMinutes(5);

    public Duration getMaxFutureSkew() {
        return maxFutureSkew;
    }

    public void setMaxFutureSkew(Duration maxFutureSkew) {
        this.maxFutureSkew = maxFutureSkew;
    }
}
