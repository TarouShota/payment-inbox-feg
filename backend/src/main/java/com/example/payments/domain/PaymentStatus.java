package com.example.payments.domain;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    /** SUCCEEDED and FAILED are final for the purpose of applying provider callbacks. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
