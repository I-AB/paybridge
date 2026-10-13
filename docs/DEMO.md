# Classroom demo script

Two parts. **Part A needs no Paystack account** and shows every outcome. **Part B** uses the real Paystack test API and a real webhook. Do A first; it is also your fallback if the network fails during the presentation.

Commands use bash `curl` (Git Bash, macOS, Linux). On Windows PowerShell use Git Bash or Postman, because PowerShell quotes JSON differently.

## Easiest way: use the dashboard
Open http://localhost:8080/ (or the deployed URL). Open an account, click **+ Add money**, pick a bank, type an account number (the simulator shortcuts fill in `...2` to `...5`), and send. The "Last API response" panel shows the raw status codes. Use **Send same request again** to demonstrate idempotency.

## Before the curl demos: you need a funded account
Transfers now come from a real PayBridge account, so `wallet-001` below must be replaced by an account number you create:
```bash
curl -s -X POST http://localhost:8080/api/accounts -H "Content-Type: application/json" -d '{"holderName":"Demo User"}'
# copy "accountNumber", e.g. 9012345678
curl -s -X POST http://localhost:8080/api/accounts/9012345678/fund \
  -H "Content-Type: application/json" -H "Idempotency-Key: fund-001" -d '{"amount":50000}'
```
Then use that number as `sourceAccount` everywhere below. (Shortcut: start with `PAYBRIDGE_ENFORCE_BALANCE=false` and `wallet-001` works unchanged, with no balance checks.)

### Extra demos this version adds
- **Money is held, then returned:** send to `0000000003` (rejected), then `GET /api/accounts/9012345678` shows the balance restored and `/entries` shows hold + refund.
- **Insufficient funds:** send more than the balance. The transfer is stored as `FAILED` and nothing is debited.
- **Timeout keeps money held:** send to `0000000004`; the balance stays reduced while the transfer is `PENDING`.
- **Webhook audit:** `GET /api/webhooks/events` lists every verified webhook and its outcome (`APPLIED`, `DUPLICATE`, `AMOUNT_MISMATCH`, ...).
- **Swagger:** `/swagger-ui.html`. **Health:** `/actuator/health`.

## Part A: offline simulator (all outcomes)

### A1. Start the app
```bash
export PAYBRIDGE_GATEWAY=simulated
export PAYSTACK_SECRET_KEY=sk_test_demo      # a dummy test key, only used to sign webhooks
./mvnw spring-boot:run
```
The log must show: `SIMULATED payment gateway is active`.

The simulator picks the outcome from the destination account number:

| Account number | What the provider does |
|----------------|------------------------|
| `0123456789` (anything else) | success |
| `0000000002` | pending |
| `0000000003` | rejects (insufficient balance) |
| `0000000004` | times out (outcome unknown) |
| `0000000005` | name enquiry fails |

### A2. Successful transfer
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-success-001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"0123456789","bankCode":"058"},"amount":5000.00,"currency":"NGN","reason":"School fees"}'
```
Expect `201`, `"status":"SUCCESS"`. Copy the `id`.

### A3. Show the transfer status
```bash
curl http://localhost:8080/api/transfers/<id>
```

### A4. Idempotency: send the exact same request again
Run the A2 command again, unchanged.
Expect `200`, header `Idempotent-Replayed: true`, and the **same id**. Only one transfer exists.

Now change only the amount but keep the key:
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-success-001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"0123456789","bankCode":"058"},"amount":9999.00,"currency":"NGN"}'
```
Expect `422 IDEMPOTENCY_KEY_REUSED`.

### A5. Provider rejects (failure)
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-failed-001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"0000000003","bankCode":"058"},"amount":100.00,"currency":"NGN"}'
```
Expect `201`, `"status":"FAILED"`, with a `failureReason`.

### A6. Provider pending, then completed by a webhook
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-pending-001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"0000000002","bankCode":"058"},"amount":100.00,"currency":"NGN"}'
```
Expect `202`, `"status":"PENDING"`. Copy `id` and `reference`.

Send a signed webhook, exactly like Paystack would (the signature is HMAC SHA512 of the body with your secret key):
```bash
BODY='{"event":"transfer.success","data":{"reference":"<reference>","transfer_code":"TRF_demo","status":"success"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha512 -hmac 'sk_test_demo' | awk '{print $NF}')
curl -i -X POST http://localhost:8080/api/webhooks/paystack \
  -H "Content-Type: application/json" -H "x-paystack-signature: $SIG" -d "$BODY"
curl http://localhost:8080/api/transfers/<id>        # now SUCCESS
```
Send the same webhook again: still `200`, status unchanged (duplicate ignored).
Send it with `-H "x-paystack-signature: wrong"`: `401`.
Replace `transfer.success` by `transfer.failed` on a new pending transfer to show the failed path.

### A7. Provider timeout (outcome unknown) and sync
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-timeout-001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"0000000004","bankCode":"058"},"amount":100.00,"currency":"NGN"}'
```
Expect `202 PENDING` (not FAILED, because the money may have moved). Retry with the same key: `200`, same id, provider not called again. Then:
```bash
curl -X POST http://localhost:8080/api/transfers/<id>/sync     # asks the provider, becomes SUCCESS
```

### A8. Validation
```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-bad-0001" \
  -d '{"sourceAccount":"wallet-001","destination":{"accountNumber":"123","bankCode":"058"},"amount":-5,"currency":"ngn"}'
```
Expect `400 VALIDATION_FAILED` with one entry per bad field. Omit the `Idempotency-Key` header: `400 MISSING_HEADER`.

## Part B: real Paystack test API

### B1. Get a test key
1. Create a Paystack account and switch the dashboard to **Test mode**.
2. Settings, API Keys & Webhooks: copy the **Test Secret Key** (`sk_test_...`). Never commit it.
3. In test mode, check the Transfers settings. If transfers ask for an OTP, the API answers with an `otp` status and the transfer will not complete on its own. Turn the OTP requirement off for test mode if the dashboard offers it. (Dashboard wording changes; this was not re-checked.)

### B2. Start against Paystack
```bash
unset PAYBRIDGE_GATEWAY
export PAYSTACK_SECRET_KEY=sk_test_xxxxxxxxxxxx     # your real TEST key, typed in your terminal only
./mvnw spring-boot:run
```
Send the A2 request again with a **new** `Idempotency-Key`. Use a test bank and account number from the Paystack documentation. If name enquiry fails in test mode, restart with `PAYBRIDGE_RESOLVE_ACCOUNT=false`.

### B3. Expose the app for webhooks
Paystack must reach your laptop from the internet.
```bash
ngrok http 8080
```
ngrok prints a URL such as `https://abcd-12-34.ngrok-free.app`. In the Paystack dashboard, Settings, API Keys & Webhooks, set the **Test Webhook URL** to:
```
https://abcd-12-34.ngrok-free.app/api/webhooks/paystack
```
When a test transfer finishes, Paystack posts `transfer.success`, `transfer.failed` or `transfer.reversed` to that URL. Watch the app log for `Webhook for transfer ... applied`. Then `GET /api/transfers/<id>`.
If no webhook arrives, `POST /api/transfers/<id>/sync` fetches the status directly.

### B4. Optional live sandbox test
```bash
./mvnw test -Dtest=PaystackSandboxLiveTest -Dsandbox=true
```

## Suggested 5 minute order for your presentation
1. Show the package tree and say the module rule (1 min).
2. A2 success, A3 read back (30 s).
3. A4 idempotency, same key twice (45 s).
4. A5 failure and A7 timeout, explain why timeout stays PENDING (1 min).
5. A6 pending then webhook, and the bad signature (1 min).
6. `./mvnw test` green, mention `ModuleBoundaryTest` (45 s).
7. Part B only if the network is reliable.
