package com.academy.paybridge.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param resolveAccountBeforeSend when true, the destination account name is looked up with the
 *                                 provider before any money is sent (name enquiry)
 * @param enforceSourceBalance     when true, the source must be a PayBridge account: the amount is
 *                                 held (debited) before sending and returned if the transfer fails
 */
@ConfigurationProperties(prefix = "paybridge.transfer")
public record TransferProperties(
        @DefaultValue("true") boolean resolveAccountBeforeSend,
        @DefaultValue("true") boolean enforceSourceBalance) {
}
