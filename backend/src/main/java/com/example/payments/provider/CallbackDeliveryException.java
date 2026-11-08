package com.example.payments.provider;

/** A callback delivery attempt failed; the relay decides whether to retry or dead-letter it. */
public class CallbackDeliveryException extends RuntimeException {

    public CallbackDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
