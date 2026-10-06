package com.academy.paybridge.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param resolveAccountBeforeSend when true, the destination account name is looked up with the
 *                                 provider before any money is sent (name enquiry)
 */
@ConfigurationProperties(prefix = "paybridge.transfer")
public record TransferProperties(@DefaultValue("true") boolean resolveAccountBeforeSend) {
}
