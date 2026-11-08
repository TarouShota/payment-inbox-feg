package com.example.payments.service;

public class DuplicateEventException extends RuntimeException {

    public DuplicateEventException(String eventId, Throwable cause) {
        super("Duplicate eventId: " + eventId, cause);
    }
}
