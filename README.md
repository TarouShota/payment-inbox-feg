# Payment Inbox

[![CI](https://github.com/TarouShota/payment-inbox-feg/actions/workflows/ci.yml/badge.svg)](https://github.com/TarouShota/payment-inbox-feg/actions/workflows/ci.yml)

A small operator app for card payments. The operator starts a payment; the fake provider returns a
provider payment id immediately, so the service answers `201` with a `PENDING` payment. The
provider reports the outcome asynchronously through a callback, normally 2–5 seconds later. The
operator follows the status until it reaches a final state.

- **Backend**: Java 21, Spring Boot 3.3, Gradle wrapper, Spring Web, Spring Data JPA, H2 in-memory.
- **Frontend**: Vue 3 + TypeScript + Vite, one page, no UI library.
- Ports: backend **8080**, frontend **3000**. In dev, Vite proxies `/payments` to the backend; in
  Docker, nginx does the same.

## Screenshots

| Pending — polling every 2 s | Succeeded — polling stopped |
|---|---|
| ![Pending payment](docs/screenshots/pending.png) | ![Succeeded payment](docs/screenshots/succeeded.png) |

![Backend unreachable while polling](docs/screenshots/unreachable.png)

## How it works

```mermaid
sequenceDiagram
    participant UI as Vue page (:3000)
    participant API as Spring Boot (:8080)
    participant P as Fake provider
    participant R as Callback relay
    participant DB as H2

    UI->>API: POST /payments
    API->>P: authorize(amount, currency, idempotencyKey)
    P-->>API: prov-<uuid> immediately
    API->>DB: insert payment + callback_outbox (same transaction)
    API-->>UI: 201 PENDING
    Note over P,DB: callback is due 2–5 s after the payment commits
    Note over R,DB: the relay polls due rows every 100 ms by default
    R->>API: POST /provider-callbacks over HTTP
    API->>DB: lock row, dedupe eventId, order by occurredAt
    API-->>R: 200 APPLIED
    R->>DB: mark outbox row SENT (or retry / DEAD)
    loop every 2 s while PENDING or UNKNOWN
        UI->>API: GET /payments/{id}
        API-->>UI: current status
    end
```

## Security and consistency

- Money is `BigDecimal` in the request, domain, provider call and response; the DB column is
  `NUMERIC(19,2)`. Validation rejects zero, negative amounts, more than two decimals and malformed
  currencies; `setScale(2)` only pads zeros, it never rounds. The frontend's `12.50` is a
  placeholder for the expected shape, sends the raw string and only formats the returned value for
  display; it does no money arithmetic.
- Callback processing locks the payment row, deduplicates `eventId` in a global table, and only
  moves the `occurredAt` watermark when a callback is actually applied.
- The payment and its outbound callback are written in one transaction to a `callback_outbox`
  table. A relay sends due rows with bounded exponential backoff and parks a row as `DEAD` after
  the configured attempts, so a provider outage does not silently drop a callback.
- `Idempotency-Key` is a unique database column plus a request fingerprint, so concurrent retries
  cannot create two payments.
- Callback HMAC is optional and off by default to keep the assignment contract; when on it uses a
  timestamp window and constant-time comparison. No secrets are committed.
- Logs carry a generated or safe inbound `X-Request-Id`; customer references and secrets are not
  logged. In-memory H2 is demo-only — the production gaps are listed in [NOTES.md](NOTES.md).

## Requirements

- JDK 21 (`JAVA_HOME` set to it)
- Node.js 20+ with npm

Docker is optional. If you have it, Compose is the most reproducible path and does not need a local
JDK or Node install. It needs Docker Engine with the Compose plugin and network access for the first
build (base images, Gradle, Maven and npm).

Tested with Temurin 21.0.13+9, Gradle 8.10.2 (wrapper), Node 22.22.0, npm 10.9.4, Docker 29.9.2
and Compose v5.6.1.

## Run with Docker

The Compose setup builds the backend with the committed Gradle wrapper, builds the page with Node
22, and serves it from nginx. You do not need a local JDK or Node install.

The stack has two containers: the Spring Boot backend with embedded H2, and nginx serving the built
page. There is no separate database container; H2 is the local storage option from the brief.

```bash
docker compose up --build
```

Open <http://localhost:3000>. The backend is also on <http://localhost:8080> (Swagger UI at
<http://localhost:8080/swagger-ui.html>). The frontend container proxies `/payments` to the
backend, so the browser does not need CORS. Stop it with `docker compose down`.

The first build pulls the base images and downloads Gradle and npm dependencies, so it needs
network once. Runtime is local. If ports 8080 or 3000 are already in use, stop the local dev
servers first.

The provider outcome works the same way:

```bash
APP_PROVIDER_OUTCOME=DECLINED docker compose up --build
```

The backend container waits on its healthcheck before the frontend starts. H2 is still in memory,
so `docker compose down` discards payments and pending outbox rows.

## Run

Backend:

```bash
cd backend
./gradlew bootRun
```

Frontend (in a second terminal):

```bash
cd frontend
npm ci
npm run dev
```

Open <http://localhost:3000>. The first build needs Maven Central and npm once; runtime is fully
local and makes no external calls. Create requests time out after 10 seconds, status polls after
8 seconds, and a failed create leaves the payment that was already being watched polling.

## Test

```bash
cd backend
./gradlew test
```

107 backend tests: payment API validation and contract, all callback rules (including equal
timestamps), the suppressed-callback decision proof, concurrency (two racing callbacks, duplicate
event ids, simultaneous idempotent creates), signed-callback authentication, outbox delivery,
retry backoff and dead-lettering, real-HTTP end-to-end tests of the fake provider, the optional
future-skew guard and its default-off behavior, and the generated OpenAPI document.

Seven frontend tests cover the polling lifecycle: 2-second polling, polling through `UNKNOWN`,
stopping at `SUCCEEDED` or `FAILED`, aborting the old request when a new payment starts, retrying
after a network failure, and clearing timers on unmount. Type-check and production build:

```bash
cd frontend
npm test
npm run build
```

The Docker image builds with `-x test`, so run these suites before
`docker compose up --build` if you want the tests in the loop.

Both suites run in GitHub Actions on every push to `main` and every pull request
(`.github/workflows/ci.yml`).

## API docs

With the backend running:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

The schema is generated from the controllers and DTOs, so it stays in step with the code. It is
not a replacement for the behavioural tests above.

## Fake provider

The provider is a class in the backend (`provider/FakePaymentProvider`). It returns a
`prov-<uuid>` id immediately and writes the callback into `callback_outbox` in the payment
transaction. A scheduled relay delivers due rows over HTTP to `POST /provider-callbacks` after
2–5 seconds. A failed delivery retries with exponential backoff; after `APP_PROVIDER_MAX_ATTEMPTS`
it is parked as `DEAD` instead of retrying forever.

Configure the reported outcome with environment variables (restart to apply):

```bash
APP_PROVIDER_OUTCOME=CAPTURED ./gradlew bootRun   # default -> payment SUCCEEDED
APP_PROVIDER_OUTCOME=DECLINED ./gradlew bootRun   # -> payment FAILED
APP_PROVIDER_OUTCOME=ERROR    ./gradlew bootRun   # -> payment UNKNOWN
APP_PROVIDER_OUTCOME=WHATEVER ./gradlew bootRun   # anything unrecognised -> payment UNKNOWN
```

With Docker, prefix the same variable to `docker compose up --build`, or use
`docker compose up -d backend` to recreate only the backend after changing it.

Other knobs:

| Variable | Default | What it does |
|---|---|---|
| `APP_PROVIDER_MIN_DELAY_MS` | `2000` | earliest callback delay |
| `APP_PROVIDER_MAX_DELAY_MS` | `5000` | latest callback delay |
| `APP_PROVIDER_CALLBACK_URL` | `http://localhost:8080/provider-callbacks` | where the relay posts |
| `APP_PROVIDER_AUTO_CALLBACK` | `true` | tests switch it off to drive callbacks directly |
| `APP_PROVIDER_CONNECT_TIMEOUT_MS` | `2000` | callback HTTP connect timeout |
| `APP_PROVIDER_READ_TIMEOUT_MS` | `5000` | callback HTTP read timeout |
| `APP_PROVIDER_RELAY_INTERVAL_MS` | `100` | how often due outbox rows are checked |
| `APP_PROVIDER_MAX_ATTEMPTS` | `5` | attempts before the row becomes `DEAD` |
| `APP_PROVIDER_INITIAL_RETRY_MS` | `500` | first retry delay; doubles each attempt |
| `APP_PROVIDER_MAX_RETRY_MS` | `30000` | cap for the retry delay |

`APP_CALLBACK_MAX_FUTURE_SKEW` is off by default (`0s`), so a structurally valid future
`occurredAt` follows the normal ordering rules. Set it to e.g. `5m` to reject timestamps further
ahead with `400`, so a bad provider clock cannot freeze the watermark.

### Callback authentication (opt-in)

Callback auth is off by default so the assignment contract stays unchanged. Enable it with:

```bash
APP_CALLBACK_AUTH_ENABLED=true APP_CALLBACK_AUTH_SECRET="<shared secret>" ./gradlew bootRun
```

With Docker, the same variables work with `docker compose up --build`; they are forwarded in
`docker-compose.yml`.

The fake provider signs automatically when auth is on. External callers must send:

- `X-Provider-Timestamp`: epoch seconds
- `X-Provider-Signature`: lowercase hex HMAC-SHA256 of `<timestamp>.<raw JSON body>`

The timestamp is part of the signed payload and must be within `APP_CALLBACK_AUTH_MAX_AGE`
(default `5m`) of the server clock, so a captured request cannot be replayed later. Signatures are
compared in constant time. Missing, tampered, or stale signatures get `401` with
`{"error":"invalid_signature"}` and are not recorded, so the same `eventId` can still arrive
correctly later.

```bash
TS=$(date +%s)
BODY='{"eventId":"evt-1","providerPaymentId":"prov-...","status":"CAPTURED","occurredAt":"2026-11-08T12:00:00Z"}'
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$APP_CALLBACK_AUTH_SECRET" -hex | sed 's/^.* //')
curl -i -X POST http://localhost:8080/provider-callbacks \
  -H 'Content-Type: application/json' \
  -H "X-Provider-Timestamp: $TS" \
  -H "X-Provider-Signature: $SIG" \
  -d "$BODY"
```

In production the secret would come from a secret manager and be rotated; this is a shared-secret
HMAC, not mTLS or per-provider key management. With auth enabled and a blank secret the service
refuses to start.

Every request gets an `X-Request-Id` header (incoming safe ids are reused, otherwise one is
generated). Each accepted callback logs a `callback outcome=...` line with the event id, payment
id and result; duplicates are logged at `DEBUG`, and rejected callbacks log
`callback rejected reason=...`. Customer references are not logged.

## API

### `POST /payments`

```bash
curl -i -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"amount": 12.50, "currency": "EUR", "customerReference": "ORDER-1001"}'
```

- `amount`: positive, at most 2 decimals (JSON number or numeric string, kept exact with
  `BigDecimal`). `12.50` is stored exactly; `12.5` is padded to `12.50`; `12.505` is rejected
  with `400` — the service does not round.
- `currency`: exactly 3 letters, normalised to uppercase
- `customerReference`: non-blank, max 255 chars
- optional `Idempotency-Key` header (1–128 safe characters)

Retries with the same `Idempotency-Key` and the same data return the original payment: `201`, the
same `Location`, plus `Idempotency-Replayed: true`. The body is the payment's current state, so if
a callback has already advanced it, `status`, `providerStatus` and `lastCallbackOccurredAt` reflect
that. A replay that finds the stored payment does not call the provider again. Equivalent amount formatting (`10.0` vs `10.00`) and currency case
count as the same request. Reusing a key with different data returns `409 idempotency_key_reuse`; a
malformed key returns `400 invalid_idempotency_key`. Without the header, every request creates a
payment. The unique key column also settles two simultaneous first attempts: one creates, the other
replays. Both may reach the fake provider, but it derives the provider id from the key, so they
cannot create two different provider authorizations.

```bash
curl -i -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-1042' \
  -d '{"amount": 12.50, "currency": "EUR", "customerReference": "ORDER-1001"}'
```

`201 Created` with a `Location` header and:

```json
{
  "id": "d663626f-22e4-4316-9170-94d1dba57459",
  "status": "PENDING",
  "providerStatus": null,
  "providerPaymentId": "prov-01129ebf-f15e-4e51-a9c3-5418e735c299",
  "lastCallbackOccurredAt": null,
  "amount": 12.50,
  "currency": "EUR",
  "customerReference": "ORDER-1001"
}
```

Invalid payloads return `400`:

```json
{"error": "validation_failed", "message": "Request payload is invalid", "fieldErrors": {"amount": "must be greater than 0"}}
```

### `GET /payments/{id}`

Returns the same shape. `404` for an unknown id, `400` for a malformed id.

### `POST /provider-callbacks`

```bash
curl -i -X POST http://localhost:8080/provider-callbacks \
  -H 'Content-Type: application/json' \
  -d '{"eventId": "evt-1", "providerPaymentId": "prov-...", "status": "CAPTURED", "occurredAt": "2026-11-08T12:00:00Z"}'
```

- `200` with `{"result": "APPLIED" | "DUPLICATE" | "IGNORED" | "SUPPRESSED"}` — accepted even when
  the callback changes nothing.
- `404 unknown_provider_payment` when `providerPaymentId` is unknown (before dedupe, and not recorded).
- `400` for invalid payloads (`eventId`/`providerPaymentId` required, `occurredAt` required ISO-8601
  instant with offset, `status` optional).

When `APP_CALLBACK_AUTH_ENABLED=true`, the same request must also carry
`X-Provider-Timestamp` and `X-Provider-Signature` (see above); otherwise it is rejected with
`401` before any payment lookup or event recording.

Status mapping: `AUTHORIZED` → `PENDING`, `CAPTURED` → `SUCCEEDED`, `DECLINED` → `FAILED`,
everything else (including `null`) → `UNKNOWN`.

Rules: an `eventId` is applied once (globally); a callback with `occurredAt` equal to or older than
the last applied callback is ignored; `UNKNOWN` never replaces a known outcome, while a newer
`CAPTURED`/`DECLINED` may replace one. A late `AUTHORIZED` (which maps to `PENDING`) is suppressed
after a terminal state for the same reason: it is a pre-outcome state and must not reopen a
finished payment. A suppressed callback changes no payment field — including
`lastCallbackOccurredAt` — but its `eventId` is still recorded. The reasoning, the counterexample,
and the other design decisions are in [NOTES.md](NOTES.md).

## Project structure

```
.github/             CI workflow (backend tests, frontend tests and build)
backend/             Spring Boot service, tests, Gradle wrapper, Dockerfile
frontend/            Vue 3 + TypeScript page, polling composable, nginx Dockerfile
docker-compose.yml   Backend + frontend containers on ports 8080 and 3000
NOTES.md             Decisions, an AI mistake, production gaps
```

Backend packages: `api` (controllers, DTOs, error handling), `config` (OpenAPI metadata),
`domain` (entities, repositories), `service` (payment creation, callback rules), `provider` (fake
provider, callback outbox and relay, HTTP callback client), `security` (optional callback HMAC
authentication).

H2 is in-memory: restarting the backend discards payments and any pending outbox rows. This is
deliberate for a self-contained demo.
