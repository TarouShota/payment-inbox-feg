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

    /** Timeouts for the fake provider's callback HTTP client. */
    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 5000;

    /** Delivery attempts before a callback is parked in DEAD (the dead-letter state). */
    private int maxAttempts = 5;

    /** First retry delay; doubles on every failed attempt. */
    private long initialRetryMs = 500;

    /** Upper bound for the exponential backoff. */
    private long maxRetryMs = 30000;

    /** Test switch: when false, creating a payment enqueues no callback. */
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

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialRetryMs() {
        return initialRetryMs;
    }

    public void setInitialRetryMs(long initialRetryMs) {
        this.initialRetryMs = initialRetryMs;
    }

    public long getMaxRetryMs() {
        return maxRetryMs;
    }

    public void setMaxRetryMs(long maxRetryMs) {
        this.maxRetryMs = maxRetryMs;
    }

    public boolean isAutoCallback() {
        return autoCallback;
    }

    public void setAutoCallback(boolean autoCallback) {
        this.autoCallback = autoCallback;
    }
}
