package com.academy.paybridge.shared.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** A friendly answer for the base URL, so a browser visit shows that the service is up. */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "PayBridge transfer API");
        body.put("status", "UP");
        body.put("gateway", "simulated (no real money moves)");
        body.put("endpoints", Map.of(
                "createTransfer", "POST /api/transfers (header Idempotency-Key required)",
                "getTransfer", "GET /api/transfers/{id}",
                "syncTransfer", "POST /api/transfers/{id}/sync",
                "webhook", "POST /api/webhooks/paystack"));
        body.put("code", "https://github.com/I-AB/paybridge");
        return body;
    }
}