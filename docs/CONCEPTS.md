# Concepts you should be able to explain

Short answers, in plain language, with a pointer to the code.

**What is a transfer?**
A request to move an amount of money from one place to another, plus its history: who, how much, which reference, what state it is in. In this project it is the `Transfer` record and one row in the `transfers` table.

**Why a TransferService?**
It holds the business rules in one place: validate, check idempotency, call the provider, decide the status. The controller only handles HTTP and the repository only handles SQL, so each can change without touching the others. It is also easy to test without a web server.

**Why a TransferGateway interface?**
The service needs "something that can send money", not "Paystack". The interface is the contract; `PaystackTransferGateway` and `SimulatedTransferGateway` both fulfil it. Switching provider means writing a new class, with no change to `TransferService`.
```java
public interface TransferGateway {
    ResolvedAccount resolveAccount(String accountNumber, String bankCode);
    GatewayTransferResult send(GatewayTransferRequest request);
    GatewayTransferResult fetch(String reference);
}
```

**Why does Paystack have its own client package?**
Everything Paystack specific is in `transfer/client/paystack`: URLs, JSON field names like `account_number`, the recipient step, the status words, the HMAC signature. If Paystack changes, only that package changes. `ModuleBoundaryTest` fails if service or web code imports it.

**Why shouldn't the controller call Paystack directly?**
Then HTTP code, business rules and provider details would be mixed in one class. You could not test the rules without a provider, and you could not replace the provider.

**What is idempotency?**
Doing the same request many times has the same effect as doing it once. Networks time out and users double click, so clients retry; a retry must never send money twice.
- The client sends an `Idempotency-Key` header (a UUID it generates).
- The key is stored in the `transfers` table with a UNIQUE constraint.
- On a request we look the key up. Found with the same body: return the stored transfer, do not call the provider (status 200, `Idempotent-Replayed: true`). Found with a different body: 422.
- If two identical requests arrive at the same instant, both pass the lookup, but only one `INSERT` can succeed; the other catches `DuplicateIdempotencyKeyException` and returns the winner.
- If the first request is still PENDING, a retry just returns PENDING. It does not resend.

**Why do we need a webhook?**
A transfer may finish seconds or minutes after the API call returns. Instead of asking again and again (polling), the provider calls us when it is done. We verify the HMAC signature, find the transfer by `reference`, and update the status.

**Why can a transfer be PENDING?**
Because sending money is not instant: the bank may still be processing, or the provider may have timed out so we do not know the result. PENDING means "do not assume anything yet".

**Why not mark a timeout as FAILED?**
The request may have reached Paystack and the money may have moved. If we called it FAILED and the user retried, they could pay twice. So we wait for the webhook or use `sync`.

**Our status vs Paystack's status?**
Paystack has several words (`pending`, `otp`, `received`, `success`, `failed`, `reversed`). We keep three: PENDING, SUCCESS, FAILED. `PaystackStatusMapper` translates, treating anything unknown as PENDING. Our status is our own contract; it must not change when a provider changes its vocabulary.

**Why shouldn't money be a `double`?**
Binary floating point cannot store 0.1 exactly: `0.1 + 0.2` is `0.30000000000000004`. Money errors accumulate and are unacceptable. We store kobo as a `long` (5000.50 naira is 500050) in `Money`, which is exact. Paystack also expects the amount in kobo.

**Why shouldn't I hard-code the API key?**
Source code is shared and committed; a leaked key lets someone use your account. We read `PAYSTACK_SECRET_KEY` from the environment, refuse any key not starting with `sk_test_`, hide the key in `toString()`, and keep it out of error messages and logs.

**How does dependency injection help?**
Classes receive what they need through the constructor instead of creating it.
```java
public TransferService(TransferRepository repository, TransferGateway gateway,
                       TransferProperties properties, Clock clock)
```
Spring decides which `TransferGateway` to inject, based on `paybridge.transfer.gateway`. In tests we inject a mock or an in-memory repository. Same service code, different collaborators.

**How does the project follow the modular monolith rule?**
One application, but each module is a set of packages with a clear public face (`api/`). Other modules may import only `transfer.api.*` (`TransferApi`, `TransferView`), never `transfer.service`, `transfer.repository` and so on. `TransferView` uses plain types only (String, long) so callers need nothing else. `ModuleBoundaryTest` checks this on every build. Transfer in turn does not import any other module; it needs none of them yet.

**What is the webhook signature check?**
Paystack computes `HMAC_SHA512(secretKey, rawBody)` and sends it in `x-paystack-signature`. We compute the same value over the exact bytes received and compare in constant time. Someone without the secret key cannot produce a valid signature, so forged webhooks are rejected with 401.

**What if the facilitator asks "what is not done?"**
Be direct: no real debit from an internal account (account and ledger modules are empty), no authentication on the API, no handling of a reversal after success, and the Paystack field names should be re-checked against the live docs. See "Known limits" in the README.
