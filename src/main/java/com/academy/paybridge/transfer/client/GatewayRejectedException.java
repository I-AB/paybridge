package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** The provider refused the request. The message is safe to show to the caller. */
public class GatewayRejectedException extends PayBridgeException {

    public GatewayRejectedException(String message) {
        super(422, "PROVIDER_REJECTED", message);
    }
}
