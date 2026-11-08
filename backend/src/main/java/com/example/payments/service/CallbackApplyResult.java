package com.example.payments.service;

public enum CallbackApplyResult {
    /** The callback changed the payment. */
    APPLIED,
    /** The event id was already processed; nothing changed. */
    DUPLICATE,
    /** The callback is older than or equal to the last applied one; nothing changed. */
    IGNORED,
    /** A terminal payment was protected from an UNKNOWN/PENDING callback; nothing changed. */
    SUPPRESSED
}
