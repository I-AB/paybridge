package com.academy.paybridge.transfer.client.simulated;

import com.academy.paybridge.transfer.client.BankInfo;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferRequest;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * An offline stand-in for a payment provider, used for classroom demos and automated tests.
 * Enable it with {@code paybridge.transfer.gateway=simulated}. It makes no network calls.
 *
 * <p>Because a real sandbox almost always answers "success", this gateway lets you trigger the
 * other outcomes on demand by choosing the destination account number:
 * <pre>
 *   0000000002  provider answers PENDING (later confirmed with sync or a webhook)
 *   0000000003  provider rejects the transfer (insufficient balance)
 *   0000000004  provider times out (outcome unknown, transfer stays PENDING)
 *   0000000005  name enquiry fails (account cannot be resolved)
 *   anything else  provider answers SUCCESS
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "paybridge.transfer.gateway", havingValue = "simulated")
public class SimulatedTransferGateway implements TransferGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTransferGateway.class);

    public SimulatedTransferGateway() {
        log.warn("SIMULATED payment gateway is active. No real provider is called and no money moves.");
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        if ("0000000005".equals(accountNumber)) {
            throw new GatewayRejectedException("Could not resolve account name (simulated)");
        }
        return new ResolvedAccount(accountNumber, "SIMULATED ACCOUNT HOLDER");
    }

    @Override
    public GatewayTransferResult send(GatewayTransferRequest request) {
        String code = "SIM_" + request.reference();
        return switch (request.accountNumber()) {
            case "0000000002" -> new GatewayTransferResult(GatewayStatus.PENDING, code, "Transfer has been queued (simulated)");
            case "0000000003" -> throw new GatewayRejectedException("Insufficient balance at the provider (simulated)");
            case "0000000004" -> throw new GatewayUnavailableException("Payment provider timed out (simulated)");
            default -> new GatewayTransferResult(GatewayStatus.SUCCESS, code, "Transfer completed (simulated)");
        };
    }

    /** The simulated provider eventually completes everything, like a real one usually does. */
    @Override
    public GatewayTransferResult fetch(String reference) {
        return new GatewayTransferResult(GatewayStatus.SUCCESS, "SIM_" + reference, "Transfer completed (simulated)");
    }

    @Override
    public List<BankInfo> listBanks() {
        return List.of(
                new BankInfo("Access Bank", "044"),
                new BankInfo("Guaranty Trust Bank", "058"),
                new BankInfo("First Bank of Nigeria", "011"),
                new BankInfo("United Bank for Africa", "033"),
                new BankInfo("Zenith Bank", "057"),
                new BankInfo("Kuda Microfinance Bank", "50211"),
                new BankInfo("OPay", "999992"),
                new BankInfo("Test Bank (sandbox)", "001"));
    }
}
