package com.academy.paybridge.transfer.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The public entry point of the transfer module for other modules (ledger, notification, ...).
 * README rule: a module may only use another module through its api package.
 */
public interface TransferApi {

    Optional<TransferView> findTransfer(UUID id);
}
