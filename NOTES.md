# NOTES

## The decision: a suppressed callback does not move the watermark

`lastCallbackOccurredAt` is the timestamp of the last callback I actually applied. It is not
"the last callback I received". That difference is the whole rule 3 decision.

Concrete sequence:

| time | callback | result | payment status | lastCallbackOccurredAt |
|---|---|---|---|---|
| 12:00:10 | CAPTURED | applied | SUCCEEDED | 12:00:10 |
| 12:00:20 | ERROR, maps to UNKNOWN | suppressed | SUCCEEDED | 12:00:10 |
| 12:00:15 | DECLINED | applied | FAILED | 12:00:15 |

The DECLINED is older than the suppressed UNKNOWN, but newer than the last applied callback. That
is the case the rules care about. If the suppressed callback at 12:00:20 moved the watermark, the
DECLINED at 12:00:15 would look stale and rule 2 would drop it. We would keep showing SUCCEEDED
after the provider told us the payment was declined.

So a rule 3 suppression records the eventId, so a replay is caught by rule 1, and changes nothing
else: `status`, `providerStatus` and `lastCallbackOccurredAt` stay put. I apply the same guard to a
late AUTHORIZED after a final status, because it maps to PENDING and would reopen a finished
payment.

Regression test: `ProviderCallbackRulesTest`, CAPTURED then suppressed UNKNOWN then older DECLINED
must end FAILED. The rule 3 tests also assert every payment field is unchanged after a suppression.

## Dedupe is global

One `processed_callbacks` table keyed by eventId. Once an eventId is accepted it is done, even if
the payload changes or it points at another payment. The rule says the same eventId is applied
once, not once per payment. An unknown `providerPaymentId` is 404 before the dedupe check and is
not recorded, so the same eventId can still be retried later with the correct id. The primary key
also catches the duplicate insert race.

## Row lock on the way in, outbox on the way out

Inbound, the callback transaction locks the payment row with `PESSIMISTIC_WRITE`, then reads,
checks and writes. Two callbacks for the same payment therefore serialize. I used that instead of
`@Version` because a retry loop on version conflicts adds code for no benefit here.

Outbound, my first version scheduled the fake callback after commit. That has a gap: if the process
dies between the commit and the send, the payment exists and the callback is gone, and the HTTP
client swallowed failures. Now the listener runs `BEFORE_COMMIT` and writes a `callback_outbox` row
in the same transaction as the payment. Either both commit or neither does. A relay polls due rows,
sends over HTTP and marks them `SENT`. A failed attempt increments the count and sets
`nextAttemptAt` with exponential backoff (500 ms doubling, capped at 30 s). After 5 attempts the
row is `DEAD`, which is the dead-letter state.

Delivery is at-least-once: the relay can send, crash before marking `SENT`, and send again. That is
safe because the callback endpoint already dedupes by `eventId`. The relay is a single instance
here; production would claim rows with `FOR UPDATE SKIP LOCKED` or use a real queue, and alert on
rows going `DEAD`.

## Idempotent create

`POST /payments` accepts an optional `Idempotency-Key`. The key is unique on the payment row, with a
SHA-256 fingerprint of the normalised `amount|currency|customerReference`. Same key and same request
returns the original payment with `201`, `Idempotency-Replayed: true`, and does not call the provider
again. Same key with different data is `409`. A malformed key is `400`. Two simultaneous first
attempts are settled by the unique index: one creates, the other catches the integrity violation,
reads the winner and replays it. Without the header every request creates a payment, as before.

The reason is boring and important: network retries and double-clicks must not create two payments.

## Callback auth is opt-in

The assignment contract is unsigned, so HMAC is off by default. When enabled, the caller sends
`X-Provider-Timestamp` and `X-Provider-Signature`, where the signature is
HMAC-SHA256(`<timestamp>.<raw JSON body>`). The timestamp must be within five minutes of the server
clock, signatures are compared in constant time, and the filter runs before the controller. The path
is normalised so `/provider-callbacks;probe` cannot skip the filter. The fake provider signs its own
callbacks when auth is on.

Production still needs TLS, a secret manager, key rotation and network controls. This is a shared
secret between two systems, not mTLS or per-provider key management.

## AI mistake

The first callback implementation advanced `lastCallbackOccurredAt` for every accepted callback,
including suppressed ones. The normal tests passed because none of them had an older terminal
callback arriving after a suppressed UNKNOWN.

I wrote the 12:00:10 / 12:00:20 / 12:00:15 sequence and the test failed: DECLINED was ignored. The
fix was to update `status`, `providerStatus` and the watermark only on `APPLIED`, while still
recording the eventId on suppression. The regression test is in `ProviderCallbackRulesTest`.

## Why H2

The brief allows H2/SQLite or Compose with Postgres. I picked H2 in memory: it keeps the demo to
two containers, needs no credentials and no external calls, and supports the row lock and unique
constraints the callback rules rely on. The cost is that a restart loses data, which is the first
production gap below.

## Not production ready

- **Persistence and delivery.** H2 in memory means a restart loses payments and pending outbox
  rows. The outbox, bounded retry and `DEAD` state are there, but the relay is single-instance and
  there is no reconciliation job or admin replay for dead rows. Production needs a durable
  database, multiple workers claiming rows safely, alerts when rows go `DEAD`, and a job that
  compares provider status with ours. This is still the biggest gap.
- **Events.** `PaymentCreatedEvent` is in-process. Kafka or RabbitMQ would let other services
  consume it and survive restarts. Adding a broker only for the demo would add moving parts without
  proving the callback rules, so I left it out.
- **Ledger and audit.** Status is overwritten in place. A real payment service needs an append-only
  event history and the raw callback payloads.
- **Operator API.** No authentication, rate limiting or PII retention. Callback auth covers
  provider to us only. TLS, secret rotation and network controls are still needed.
- **Observability.** Request-id MDC and outcome logs, but no metrics, traces, dashboards or alerts.
  Production would expose latency and outcome counters and alert on callback failures and unknowns.
- **Frontend.** One page, no micro frontend or webview shell. The polling composable has unit tests,
  but there are no component or end-to-end tests.
- **Packaging.** Docker Compose builds the backend with the committed wrapper and the frontend with
  Node 22, so the demo does not depend on the reviewer's Java or Node install. It is still a demo:
  H2 in memory, no TLS, no orchestration and no production secret handling.
- **Delivery.** GitHub Actions runs the tests and builds; there is no Jenkins/OpenShift deployment or
  post-release verification.
- **Payment features.** Refunds, chargebacks, partial captures and multi-currency rounding rules are
  missing.
