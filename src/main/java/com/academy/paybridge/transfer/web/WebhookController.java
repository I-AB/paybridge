package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.client.GatewayTransferEvent;
import com.academy.paybridge.transfer.client.TransferWebhookParser;
import com.academy.paybridge.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Receives provider webhooks. The body is read as raw bytes because the signature is calculated
 * over the exact bytes sent, so the body must not be parsed or re-encoded before it is verified.
 *
 * <p>Answers 200 for every valid event (even duplicates and unknown references) so the provider
 * stops retrying. Invalid signatures get 401. The body is never logged.
 */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final TransferWebhookParser parser;
    private final TransferService transferService;

    public WebhookController(TransferWebhookParser parser, TransferService transferService) {
        this.parser = parser;
        this.transferService = transferService;
    }

    @PostMapping("/paystack")
    @Operation(summary = "Receiver for Paystack transfer events (signature verified)")
    public ResponseEntity<Void> paystack(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Paystack-Signature", required = false) String signature) {

        Optional<GatewayTransferEvent> event = parser.parse(rawBody, signature);
        event.ifPresent(transferService::applyProviderEvent);
        return ResponseEntity.ok().build();
    }
}
