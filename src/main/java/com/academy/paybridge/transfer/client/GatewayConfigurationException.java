package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** Provider credentials are missing, wrong, or not a test key. Never includes the key itself. */
public class GatewayConfigurationException extends PayBridgeException {

    public GatewayConfigurationException(String message) {
        super(503, "PROVIDER_NOT_CONFIGURED", message);
    }
}
