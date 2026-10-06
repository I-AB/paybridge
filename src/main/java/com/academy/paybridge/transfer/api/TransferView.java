package com.academy.paybridge.transfer.api;

import java.util.UUID;

/**
 * What other modules are allowed to know about a transfer. It uses only plain Java types, so no
 * other module needs to import anything from transfer's internal packages.
 */
public record TransferView(UUID id, String status, long amountMinorUnits, String currency) {
}
