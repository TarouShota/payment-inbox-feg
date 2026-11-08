package com.example.payments.service;

public class InvalidCallbackTimeException extends RuntimeException {

    public InvalidCallbackTimeException() {
        super("occurredAt is too far in the future");
    }
}
