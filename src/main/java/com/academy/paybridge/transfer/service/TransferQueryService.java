package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** Implements the module's public api, so other modules never touch the repository or TransferService. */
@Service
public class TransferQueryService implements TransferApi {

    private final TransferRepository repository;

    public TransferQueryService(TransferRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TransferView> findTransfer(UUID id) {
        return repository.findById(id).map(t -> new TransferView(
                t.id(), t.status().name(), t.amount().minorUnits(), t.amount().currency().getCurrencyCode()));
    }
}
