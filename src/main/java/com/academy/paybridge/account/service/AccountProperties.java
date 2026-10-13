package com.academy.paybridge.account.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** paybridge.accounts.* settings. */
@ConfigurationProperties(prefix = "paybridge.accounts")
public record AccountProperties(@DefaultValue("true") boolean sandboxFundingEnabled) {
}
