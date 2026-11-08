package com.example.payments.service;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1-128 characters from A-Z, a-z, 0-9 . _ : + / = -");
    }
}
