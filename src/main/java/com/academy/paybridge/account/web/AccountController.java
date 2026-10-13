package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountNotFoundException;
import com.academy.paybridge.account.api.PostingResult;
import com.academy.paybridge.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** HTTP only. Balances and ledger rules live in AccountService. */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = "PayBridge wallets. Money is held here before it is sent out.")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(summary = "Open a new NGN account with a zero balance")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse body = AccountResponse.from(accounts.open(request.holderName(), "NGN"));
        return ResponseEntity.created(URI.create("/api/accounts/" + body.accountNumber())).body(body);
    }

    @GetMapping
    @Operation(summary = "Newest accounts first")
    public List<AccountResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return accounts.list(limit).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "One account with its current balance")
    public AccountResponse get(@PathVariable String accountNumber) {
        return accounts.find(accountNumber).map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    @GetMapping("/{accountNumber}/entries")
    @Operation(summary = "Ledger statement, newest first")
    public List<LedgerEntryResponse> entries(@PathVariable String accountNumber,
                                             @RequestParam(defaultValue = "50") int limit) {
        return accounts.statement(accountNumber, limit).stream().map(LedgerEntryResponse::from).toList();
    }

    @PostMapping("/{accountNumber}/fund")
    @Operation(summary = "Add sandbox money to an account",
            description = "Idempotent: sending the same Idempotency-Key again does not add the money twice.")
    public ResponseEntity<AccountResponse> fund(@PathVariable String accountNumber,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody FundAccountRequest request) {
        long minor = request.amount().movePointRight(2).longValueExact();
        PostingResult result = accounts.fund(accountNumber, minor, idempotencyKey);
        AccountResponse body = AccountResponse.from(result.account());
        return result.applied()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
    }
}
