package com.academy.paybridge.transfer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the two things that can go wrong after a transfer is created: a webhook that never
 * arrives, and a refund that failed halfway. The actual rules live in {@link TransferService#reconcile}.
 */
@Component
@ConditionalOnProperty(name = "paybridge.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class TransferReconciler {

    private static final Logger log = LoggerFactory.getLogger(TransferReconciler.class);
    private static final int BATCH = 50;

    private final TransferService transfers;
    private final ReconcileProperties properties;

    public TransferReconciler(TransferService transfers, ReconcileProperties properties) {
        this.transfers = transfers;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${paybridge.reconcile.interval:PT1M}",
            initialDelayString = "${paybridge.reconcile.initial-delay:PT30S}")
    public void run() {
        try {
            int touched = transfers.reconcile(properties.minAge(), properties.giveUpAfter(), BATCH);
            if (touched > 0) {
                log.info("Reconciler updated {} transfer(s)", touched);
            }
        } catch (RuntimeException e) {
            log.warn("Reconciler run failed, will try again next time", e);
        }
    }
}
