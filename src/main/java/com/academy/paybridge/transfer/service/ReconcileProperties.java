package com.academy.paybridge.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the background reconciler.
 *
 * @param minAge       only transfers pending for at least this long are checked (gives webhooks time to arrive)
 * @param giveUpAfter  a transfer the provider has never heard of is failed after this long
 */
@ConfigurationProperties(prefix = "paybridge.reconcile")
public record ReconcileProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT1M") Duration interval,
        @DefaultValue("PT30S") Duration initialDelay,
        @DefaultValue("PT30S") Duration minAge,
        @DefaultValue("PT10M") Duration giveUpAfter) {
}
