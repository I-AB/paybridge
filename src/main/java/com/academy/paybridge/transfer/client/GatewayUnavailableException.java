package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** The provider could not be reached or failed. The real outcome of the request is unknown. */
public class GatewayUnavailableException extends PayBridgeException {

    public GatewayUnavailableException(String message) {
        super(503, "PROVIDER_UNAVAILABLE", message);
    }
}
