# NOTES

## Decisions

**Suppressed callbacks only record the eventId.**

If rule 3 blocks a callback, I don't touch the payment. `status`, `providerStatus` and
`lastCallbackOccurredAt` stay the same. I still insert the eventId, so a replay gets caught by
rule 1 and comes back as `DUPLICATE`.

`lastCallbackOccurredAt` is my watermark: the timestamp of the last callback I actually applied.
Rule 2 compares new callbacks against it. If a suppressed `UNKNOWN` at T20 moved it, a real
`DECLINED` with T15 would look stale and get dropped. The provider would have told us the payment
failed and we'd still show the old status.

Test case: `CAPTURED@T10` applied, `UNKNOWN@T20` suppressed, `DECLINED@T15` applied. There's a
regression test for that sequence, and the rule 3 tests check every payment field after a
suppression.

I do the same for `AUTHORIZED` after a final status. It maps to `PENDING`, and a late authorization
shouldn't reopen a finished payment.

**Dedupe is global, including callbacks that changed nothing.**

One table keyed by eventId. A repeat eventId is a duplicate even if the payload changed or it points
at another payment. I kept it global because the rule says the same eventId is applied once, not
once per payment. Unknown `providerPaymentId` gets 404 before the dedupe check and isn't recorded,
so the same eventId can be retried later with the correct id.

**Row lock in the callback transaction, provider fires after commit.**

Callbacks for the same payment can arrive together, so the transaction locks the row and does the
read, checks and write in one go. I went with that instead of `@Version` because a retry loop on
version conflicts adds code for no benefit here. The eventId primary key catches the duplicate
insert race. The fake provider schedules on `AFTER_COMMIT`, so it can't call before the payment is
saved.

## AI mistake

The first callback implementation moved `lastCallbackOccurredAt` on every accepted callback,
including suppressed ones. The normal tests passed.

I went back through rule 2 with the T10/T20/T15 sequence and wrote a test for it. It failed:
`DECLINED@T15` was ignored. Fix was to update the watermark and `providerStatus` only on `APPLIED`.
Suppressed callbacks still record the eventId. That test and the field assertions are in
`ProviderCallbackRulesTest`.

## Not production ready

- Callbacks are unsigned. Anyone who can reach the endpoint can move a payment. Production needs
  signature checks, replay protection, TLS and network controls.
- In-memory H2 and a JVM scheduler. A restart loses payments and pending callbacks. No retries,
  outbox, dead-letter queue or reconciliation.
- No ledger or audit trail. Status is overwritten in place. A real payment service needs
  append-only events and the raw callback history.
- No auth, rate limiting, PII retention, observability or idempotency keys on `POST /payments`.
  Refunds, chargebacks, partial captures and rounding rules are also missing.
