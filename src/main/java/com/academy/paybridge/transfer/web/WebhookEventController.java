package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.repository.WebhookAuditRepository;
import com.academy.paybridge.transfer.repository.WebhookAuditRepository.WebhookAuditRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of the webhook audit trail (what arrived and what we did with it). */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class WebhookEventController {

    private final WebhookAuditRepository audit;

    public WebhookEventController(WebhookAuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    @Operation(summary = "Recent verified webhook events and their outcome")
    public List<WebhookAuditRecord> events(@RequestParam(defaultValue = "50") int limit) {
        return audit.findRecent(Math.max(1, Math.min(limit, 200)));
    }
}
