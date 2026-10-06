package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.service.CreateTransferCommand;
import com.academy.paybridge.transfer.service.TransferOutcome;
import com.academy.paybridge.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * HTTP only: read the request, call the service, shape the response. No business rules and no
 * provider code here.
 *
 * <p>HTTP status of POST /api/transfers describes the request, the "status" field describes the money:
 * 201 = recorded and finished (SUCCESS or FAILED), 202 = recorded but still PENDING,
 * 200 = idempotent replay of an earlier request.
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        TransferOutcome outcome = transferService.initiate(new CreateTransferCommand(
                idempotencyKey,
                request.sourceAccount(),
                request.destination().accountNumber(),
                request.destination().bankCode(),
                request.amount(),
                request.currency(),
                request.reason()));

        Transfer transfer = outcome.transfer();
        TransferResponse body = TransferResponse.from(transfer);
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAY_HEADER, "true").body(body);
        }
        HttpStatus status = transfer.status() == TransferStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/transfers/" + transfer.id()))
                .body(body);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferService.get(id));
    }

    /** Asks the provider for the latest status. Handy when a webhook did not arrive. */
    @PostMapping("/{id}/sync")
    public TransferResponse sync(@PathVariable UUID id) {
        return TransferResponse.from(transferService.sync(id));
    }
}
