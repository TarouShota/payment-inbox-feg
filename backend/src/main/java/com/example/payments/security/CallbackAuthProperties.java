package com.example.payments.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.callback.auth")
public class CallbackAuthProperties {

    /**
     * Off by default: the assignment contract has unsigned callbacks. When on, every callback
     * must carry a valid HMAC signature and a fresh timestamp.
     */
    private boolean enabled;

    /** Shared secret used for HMAC-SHA256. Read from the environment in real deployments. */
    private String secret = "";

    /** How far a signed timestamp may be from our clock in either direction. */
    private Duration maxAge = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }
}
