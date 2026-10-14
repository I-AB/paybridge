package com.academy.paybridge.transfer.client;

/**
 * The only thing the transfer service knows about payment providers.
 *
 * <p>Paystack, Flutterwave or a simulator can each implement this interface. The service never
 * imports a provider class, so swapping the provider needs no change to business logic.
 *
 * <p>Every method may throw:
 * <ul>
 *   <li>{@link GatewayRejectedException}: the provider definitively said no (bad account, not
 *       enough balance). Nothing was sent.</li>
 *   <li>{@link GatewayUnavailableException}: timeout or provider error. The outcome is UNKNOWN,
 *       the money may have moved.</li>
 *   <li>{@link GatewayConfigurationException}: credentials are missing or wrong. Our problem.</li>
 * </ul>
 */
public interface TransferGateway {

    /** Name enquiry: confirm who owns an account before sending money to it. */
    ResolvedAccount resolveAccount(String accountNumber, String bankCode);

    /** Ask the provider to send the money. Any provider specific setup happens inside the gateway. */
    GatewayTransferResult send(GatewayTransferRequest request);

    /** Ask the provider for the latest status of a transfer we already sent. */
    GatewayTransferResult fetch(String reference);
}
