package com.academy.paybridge.transfer.client;

import java.util.Optional;

/** Verifies and decodes a provider webhook so the rest of the app only sees a neutral event. */
public interface TransferWebhookParser {

    /**
     * @param rawBody   the exact bytes received (the signature is computed over these bytes)
     * @param signature the signature header sent by the provider, may be null
     * @return the event, or empty if it is a valid event we do not care about
     * @throws InvalidWebhookException if the signature or payload is invalid
     */
    Optional<GatewayTransferEvent> parse(byte[] rawBody, String signature);
}
