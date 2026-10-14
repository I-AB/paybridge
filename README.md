# PayBridge

A Spring Boot modular monolith for learning:
local money transfer, cross-border settlement, and USD to NGN remittance.

This repository currently implements the **transfer module** (Module 3): sending money to a Nigerian bank account through a payment provider sandbox (Paystack test mode), with idempotency, pending/failed handling and webhooks.

## Module rule
A module may only use another module through its `api/` package.
Never call another module's repository or service directly.
`ModuleBoundaryTest` enforces this by reading the imports of every source file.

## Modules
| Module       | Responsibility                                      | Status |
|--------------|-----------------------------------------------------|--------|
| shared       | Money value object, global errors, config, auditing | Money, errors, clock |
| customer     | Customer onboarding and KYC profile                 | empty |
| account      | NGN and USD accounts, balances                      | empty |
| ledger       | Double-entry bookkeeping                            | empty |
| transfer     | Local account-to-account transfers                  | **implemented** |
| fx           | USD to NGN rates, quotes, rate locking              | empty |
| remittance   | Inbound USD paid out in NGN                         | empty |
| settlement   | Cross-border settlement and reconciliation          | empty |
| compliance   | AML, sanctions screening, limits                    | empty |
| notification | Email/SMS alerts via events                         | empty |

## Requirements
- JDK 25
- Maven 3.9+ (or the included `./mvnw`)
- Optional: a free Paystack account (test mode) and `ngrok` for the live sandbox demo

## Setup
```bash
git clone <your repo>   # or unzip the project and open the folder in your IDE
cd paybridge
./mvnw clean verify     # downloads dependencies, compiles, runs all tests
```

### Environment variables
Never put real values in source code or commit them. Names only:

| Variable | Meaning | Default |
|----------|---------|---------|
| `PAYSTACK_SECRET_KEY` | Your Paystack **test** secret key, `sk_test_...` | empty (app still starts, transfers fail with 503) |
| `PAYBRIDGE_GATEWAY` | `paystack` (real test API) or `simulated` (offline demo) | `paystack` |
| `PAYBRIDGE_RESOLVE_ACCOUNT` | `true` = look up the account name before sending | `true` |

See `.env`. The application refuses any key that does not start with `sk_test_`.

```bash
# macOS / Linux / Git Bash
export PAYSTACK_SECRET_KEY=your_test_secret_key
# Windows PowerShell
$env:PAYSTACK_SECRET_KEY="your_test_secret_key"
```

### Run
```bash
./mvnw spring-boot:run                              # real Paystack test API
PAYBRIDGE_GATEWAY=simulated PAYSTACK_SECRET_KEY=sk_test_demo ./mvnw spring-boot:run   # offline simulator
```
The app listens on http://localhost:8080. Data is kept in an in-memory H2 database and is lost on restart.

## API

All errors use the same shape: `{"code": "...", "message": "...", "fieldErrors": {...}}`.

### POST /api/transfers
Header `Idempotency-Key` is required (8 to 100 characters, a UUID is ideal).

```json
{
  "sourceAccount": "wallet-001",
  "destination": { "accountNumber": "0123456789", "bankCode": "058" },
  "amount": 5000.00,
  "currency": "NGN",
  "reason": "School fees"
}
```

`amount` is in naira (major units). Only `NGN` is supported. The money is stored internally as kobo (`long`).

| Situation | HTTP | Body |
|-----------|------|------|
| New transfer finished (`SUCCESS` or `FAILED`) | 201 | transfer, `Location` header |
| New transfer still `PENDING` | 202 | transfer |
| Same key and same request again | 200 | the original transfer, header `Idempotent-Replayed: true` |
| Same key, different request | 422 | `IDEMPOTENCY_KEY_REUSED` |
| Validation failure | 400 | `VALIDATION_FAILED` with `fieldErrors` |
| Missing `Idempotency-Key` | 400 | `MISSING_HEADER` |
| Account name enquiry rejected | 422 | `PROVIDER_REJECTED` (nothing saved) |
| Provider keys missing or wrong | 503 | `PROVIDER_NOT_CONFIGURED` |

The HTTP status describes the request; the `status` field describes the money.

Success response (201):
```json
{
  "id": "55a3add8-3bc4-4a18-90d0-dd4d516539ae",
  "status": "SUCCESS",
  "reference": "pb-55a3add8-3bc4-4a18-90d0-dd4d516539ae",
  "sourceAccount": "wallet-001",
  "destination": { "accountNumber": "0123456789", "bankCode": "058", "accountName": "ADA OBI" },
  "amount": 5000.00,
  "currency": "NGN",
  "reason": "School fees",
  "failureReason": null,
  "createdAt": "2026-10-06T01:42:51.605677440Z",
  "updatedAt": "2026-10-06T01:42:51.607660359Z"
}
```
Pending (202): same shape with `"status": "PENDING"`.
Provider failure (201): `"status": "FAILED"` and `"failureReason": "Your balance is not enough to fulfil this request"`.
Validation failure (400):
```json
{ "code": "VALIDATION_FAILED", "message": "Request validation failed",
  "fieldErrors": { "amount": "amount must be at least 0.01" } }
```

### GET /api/transfers/{id}
Returns the transfer, or 404 `TRANSFER_NOT_FOUND`.

### POST /api/transfers/{id}/sync
Asks the provider for the latest status of a `PENDING` transfer and updates it. Use it when no webhook arrives.

### POST /api/webhooks/paystack
Called by Paystack, not by you. Requires the `x-paystack-signature` header. Returns 200 for every valid event (including duplicates), 401 for a bad signature, 400 for a malformed payload.

## Architecture

```
Client
  -> TransferController (web)            HTTP, DTOs, status codes
  -> TransferService (service)           validation, idempotency, status rules
  -> TransferGateway (client, interface) provider neutral
       -> PaystackTransferGateway        resolve, recipient, transfer, verify
       -> SimulatedTransferGateway       offline demo
  -> TransferRepository (repository)     SQL, unique idempotency key

Paystack -> WebhookController -> TransferWebhookParser (HMAC check) -> TransferService
```

```
transfer/
  api/         TransferApi, TransferView          the only part other modules may use
  domain/      Transfer, TransferStatus, exceptions
  repository/  TransferRepository, JdbcTransferRepository
  service/     TransferService, TransferQueryService, command and result types
  client/      TransferGateway + neutral types
    paystack/  everything that knows Paystack (URLs, JSON fields, HMAC)
    simulated/ offline gateway
  web/         TransferController, WebhookController, request/response DTOs
shared/
  money/       Money (long minor units + currency)
  exception/   PayBridgeException, ApiError, GlobalExceptionHandler
  config/      Clock bean
```

Design decisions in short:
- **Money** is a `long` of kobo, never `double`.
- **Statuses** are `PENDING`, `SUCCESS`, `FAILED`. A transfer leaves `PENDING` once; a duplicate or late webhook cannot change a final status (`UPDATE ... WHERE status = 'PENDING'`).
- **Timeout is not failure.** If the provider cannot be reached the outcome is unknown, so the transfer stays `PENDING` instead of being failed and retried (which could pay twice).
- **Idempotency** uses the `Idempotency-Key` header, a UNIQUE column in the database, and a fingerprint of the request body to detect key reuse.
- **Persistence** is H2 with `JdbcClient`. The table script is `db/migration/V1__create_transfers.sql` and is run by Spring SQL init, so no Flyway dependency is needed. It is written to be handed to Flyway later.

## Tests
```bash
./mvnw test                                                       # everything except the live sandbox test
./mvnw test -Dtest=PaystackSandboxLiveTest -Dsandbox=true         # OPT IN: calls the real Paystack TEST API
```
Ordinary tests never call Paystack. They use a mock HTTP server, a mocked gateway, or the offline simulator.

| Test class | What it proves |
|------------|----------------|
| `MoneyTest` | exact conversion, no rounding errors |
| `TransferServiceTest` | success, pending, failure, timeout, validation, idempotency, race, sync, webhook events |
| `TransferControllerTest` | status codes, JSON, validation errors, error shape |
| `WebhookControllerTest`, `PaystackWebhookParserTest` | signature check, event decoding, bad input |
| `PaystackTransferGatewayTest` | Paystack request shapes, status mapping, error handling, no key leaks |
| `PayBridgeTransferEndToEndTest` | whole app with real H2 database and the simulator |
| `ModuleBoundaryTest` | the module rule and "service does not know Paystack" |

## Paystack sandbox
See `docs/DEMO.md` for the full demo, including ngrok and the webhook URL.

## Known limits (honest list)
- The `account` and `ledger` modules are empty, so `sourceAccount` is only recorded. Nothing is debited. Paystack pays out of your Paystack test balance.
- The API has no authentication (out of scope for this module).
- A `transfer.reversed` event after a transfer already succeeded is not modelled.
- If the app crashes between saving a transfer and calling the provider, the transfer stays `PENDING` with no provider code. `POST /api/transfers/{id}/sync` is the manual recovery.
- Paystack request and response field names come from the author's knowledge of the public API. The documentation pages could not be re-read while building. Re-check them at https://paystack.com/docs/api/ if a call misbehaves.
