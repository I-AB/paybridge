# PayBridge

A Spring Boot modular monolith for learning:
local money transfer, cross-border settlement, and USD to NGN remittance.

This repository implements **Module 3**: sending money from a PayBridge account to a Nigerian bank account through a payment provider sandbox (Paystack test mode).

What it does:
- **Accounts and an append-only ledger.** Open NGN accounts, add sandbox money, read a statement. Every movement is a ledger line with a unique reference, so replays and refunds can never apply twice.
- **Transfers that really move balances.** The amount is held on the source account before the provider is called, and returned exactly once if the transfer fails.
- **Idempotency, pending and failed handling, signed webhooks** (with an amount cross-check and an audit trail).
- **Background reconciler** that settles transfers stuck in `PENDING` and retries refunds that did not complete.
- **Bank list and name enquiry**, Swagger UI, health check, a browser dashboard at `/`, and PostgreSQL support through environment variables.

Live demo and API docs: the app serves the dashboard at `/` and Swagger UI at `/swagger-ui.html`.

## Module rule
A module may only use another module through its `api/` package.
Never call another module's repository or service directly.
`ModuleBoundaryTest` enforces this by reading the imports of every source file.

## Modules
| Module       | Responsibility                                      | Status |
|--------------|-----------------------------------------------------|--------|
| shared       | Money value object, global errors, config, auditing | Money, errors, clock |
| customer     | Customer onboarding and KYC profile                 | empty |
| account      | NGN accounts, balances, sandbox top-up              | **implemented** |
| ledger       | Append-only record of every balance movement        | **implemented** (single-entry) |
| transfer     | Bank transfers, gateway, webhooks, reconciler       | **implemented** |
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
| `PAYBRIDGE_ENFORCE_BALANCE` | `true` = source must be a PayBridge account with enough balance | `true` |
| `PAYBRIDGE_SANDBOX_FUNDING` | `true` = allow `POST /api/accounts/{n}/fund` | `true` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Point at PostgreSQL to keep data, e.g. `jdbc:postgresql://host:5432/paybridge` | in-memory H2 |

See `.env.example` (copy it to `.env` locally; `.env` is git-ignored). The application refuses any key that does not start with `sk_test_`.

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
The app listens on http://localhost:8080: dashboard at `/`, Swagger at `/swagger-ui.html`, health at `/actuator/health`.
By default data is kept in an in-memory H2 database and is lost on restart.

**Keep data with PostgreSQL** (the same SQL scripts run on both databases):
```bash
docker compose up -d        # starts a local PostgreSQL from docker-compose.yml
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/paybridge \
SPRING_DATASOURCE_USERNAME=paybridge SPRING_DATASOURCE_PASSWORD=paybridge \
PAYBRIDGE_GATEWAY=simulated PAYSTACK_SECRET_KEY=sk_test_demo ./mvnw spring-boot:run
```
A hosted database such as Neon works the same way: use its JDBC URL (with `?sslmode=require`).

## API

All errors use the same shape: `{"code": "...", "message": "...", "fieldErrors": {...}}`.

### Other endpoints
| Endpoint | Purpose |
|----------|---------|
| `POST /api/accounts` `{"holderName"}` | open an NGN account (number starts with `90`) |
| `GET /api/accounts`, `GET /api/accounts/{number}` | list / read balances |
| `POST /api/accounts/{number}/fund` + `Idempotency-Key` `{"amount"}` | add sandbox money (idempotent) |
| `GET /api/accounts/{number}/entries` | ledger statement, newest first |
| `GET /api/transfers?limit=` | recent transfers |
| `GET /api/banks`, `GET /api/banks/resolve?accountNumber=&bankCode=` | bank list, name enquiry |
| `GET /api/webhooks/events` | audit trail of verified webhooks and their outcome |
| `GET /actuator/health` | liveness |

With enforcement on, `POST /api/transfers` needs `sourceAccount` to be a PayBridge account number: unknown account gives 404 `ACCOUNT_NOT_FOUND`; a balance that is too low gives a stored `FAILED` transfer ("Insufficient funds in the source account") and nothing is debited.

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

## Sending to another PayBridge account

Choose the bank `PayBridge wallet (another PayBridge account)` (bank code `000000`) and enter the other account's 10-digit number (starts with `90`). No provider is called: the debit and the credit happen together in one database transaction, so either both happen or neither does. The transfer is final (`SUCCESS`, or `FAILED` if the balance is too low) in the same response. Sending to a PayBridge number through any real bank code still goes through the gateway and does not credit the other account.

## Architecture

```
Client
  -> TransferController (web)            HTTP, DTOs, status codes
  -> TransferService (service)           validation, idempotency, status rules
  -> TransferGateway (client, interface) provider neutral
       -> PaystackTransferGateway        resolve, recipient, transfer, verify
       -> SimulatedTransferGateway       offline demo
  -> TransferRepository (repository)     SQL, unique idempotency key

Paystack -> WebhookController -> TransferWebhookParser (HMAC check) -> TransferService -> webhook_events (audit)
TransferReconciler (scheduled) -> TransferService.reconcile -> gateway.fetch / refund

TransferService -> AccountApi (hold, refund) -> AccountService -> LedgerApi -> ledger_entries
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
account/       api/ AccountApi ...   service/ AccountService   repository/   web/ AccountController
ledger/        api/ LedgerApi ...    service/ LedgerService    repository/
shared/
  money/       Money (long minor units + currency)
  exception/   PayBridgeException, ApiError, GlobalExceptionHandler
  config/      Clock, scheduling, OpenAPI
```

Design decisions in short:
- **Money** is a `long` of kobo, never `double`.
- **Statuses** are `PENDING`, `SUCCESS`, `FAILED`. A transfer leaves `PENDING` once; a duplicate or late webhook cannot change a final status (`UPDATE ... WHERE status = 'PENDING'`).
- **Timeout is not failure.** If the provider cannot be reached the outcome is unknown, so the transfer stays `PENDING` instead of being failed and retried (which could pay twice).
- **Idempotency** uses the `Idempotency-Key` header, a UNIQUE column in the database, and a fingerprint of the request body to detect key reuse.
- **Persistence** is `JdbcClient` over H2 (default) or PostgreSQL. The scripts `db/migration/V1..V3` are run by Spring SQL init and are written with `IF NOT EXISTS`, so they are safe on every start and can be handed to Flyway later.
- **Balances** change only inside one database transaction that locks the account row (`SELECT ... FOR UPDATE`), checks the ledger reference, updates the balance and appends the ledger line. A `CHECK (balance >= 0)` is the last line of defence.
- **Hold, then settle.** The amount is debited under reference `hold:<transfer>`; a failure credits it back under `refund:<transfer>`. Both references are unique, so a duplicate webhook, a retry or the reconciler cannot refund twice. A timeout keeps the money held.
- **Webhook amount check.** An event whose `data.amount` (kobo) differs from the stored amount is recorded as `AMOUNT_MISMATCH` and not applied.

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
| `PayBridgeTransferEndToEndTest` | whole app with real H2 database and the simulator (balance enforcement off) |
| `AccountLedgerIntegrationTest` | balances, idempotent postings, refunds, 25 concurrent debits cannot overdraw |
| `TransferFundsEndToEndTest` | hold and refund through the API, insufficient funds, double webhook, amount mismatch, banks, health |
| `TransferReconcileTest` | stale PENDING settled, give-up window, retried refunds |
| `ModuleBoundaryTest` | the module rule and "service does not know Paystack" |

## Paystack sandbox
See `docs/DEMO.md` for the full demo, including ngrok and the webhook URL.

## Known limits (honest list)
- The ledger is single-entry (one line per account movement), not full double-entry bookkeeping with a counter-account.
- The API has no authentication, customers or rate limiting; anyone who can reach it can use any account. Fine for a sandbox demo, not for production.
- `POST /api/accounts/{n}/fund` creates sandbox money from nothing. Switch it off with `PAYBRIDGE_SANDBOX_FUNDING=false`.
- Paystack pays out of your Paystack test balance; PayBridge balances are the app's own bookkeeping.
- A `transfer.reversed` event after a transfer already succeeded is not modelled.
- Hold and send are separate steps. If the app crashes in between, the transfer stays `PENDING`; the reconciler asks the provider and, once the give-up window passes with no record, fails it and returns the money.
- Paystack request and response field names come from the author's knowledge of the public API and were never exercised against the live sandbox from this project's build environment. Re-check them at https://paystack.com/docs/api/ if a call misbehaves.
