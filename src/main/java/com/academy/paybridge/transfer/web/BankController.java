package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.client.BankInfo;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.service.BankLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/banks")
@Tag(name = "Banks", description = "Bank list and account-name lookup (name enquiry)")
public class BankController {

    private final BankLookupService banks;

    public BankController(BankLookupService banks) {
        this.banks = banks;
    }

    @GetMapping
    @Operation(summary = "Banks you can send money to")
    public List<BankInfo> list() {
        return banks.banks();
    }

    @GetMapping("/resolve")
    @Operation(summary = "Who owns this account? (name enquiry)")
    public ResolvedAccount resolve(@RequestParam String accountNumber, @RequestParam String bankCode) {
        return banks.resolve(accountNumber, bankCode);
    }
}
