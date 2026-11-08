package com.example.payments.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.provider")
public class ProviderProperties {

    /** Raw status the fake provider reports. CAPTURED/DECLINED are known; anything else maps to UNKNOWN. */
    private String outcome = "CAPTURED";

    /** Where the fake provider posts its callback. */
    private String callbackUrl = "http://localhost:8080/provider-callbacks";

    private long minDelayMs = 2000;

    private long maxDelayMs = 5000;

    /** Test switch: when false, creating a payment schedules no callback. */
    private boolean autoCallback = true;

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public long getMinDelayMs() {
        return minDelayMs;
    }

    public void setMinDelayMs(long minDelayMs) {
        this.minDelayMs = minDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public boolean isAutoCallback() {
        return autoCallback;
    }

    public void setAutoCallback(boolean autoCallback) {
        this.autoCallback = autoCallback;
    }
}
