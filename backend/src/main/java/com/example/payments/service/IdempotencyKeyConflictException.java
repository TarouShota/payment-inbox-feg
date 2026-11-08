package com.example.payments.service;

/** The client reused an idempotency key for a request with different data. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("Idempotency-Key was already used for a different request");
    }
}
