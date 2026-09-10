/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.channel.api.ChannelTestResult;
import ai.kompile.channel.api.TelegramDiagnosticsView;
import ai.kompile.channel.api.TelegramPairingApprovalRequest;
import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.channel.api.TelegramWebhookInfoView;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService.ChannelConnectionConflictException;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService.ChannelConnectionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Admin-only control plane for persistent external channel connections. */
@RestController
@RequestMapping("/api/channel-integrations")
public class ChannelIntegrationController {

    private final ChannelIntegrationService service;

    public ChannelIntegrationController(ChannelIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public List<ChannelProviderDescriptor> providers() {
        return service.providers();
    }

    @GetMapping("/providers/{providerId}/auth")
    public ChannelProviderAuthView providerAuth(@PathVariable String providerId) {
        return service.providerAuth(providerId);
    }

    @GetMapping("/engines")
    public List<ChannelEngineDescriptor> engines() {
        return service.engines();
    }

    @GetMapping("/connections")
    public List<ChannelConnectionView> connections() {
        return service.list();
    }

    @PostMapping("/connections")
    public ResponseEntity<ChannelConnectionView> create(
            @RequestBody ChannelConnectionRequest request) {
        ChannelConnectionView created = service.create(request);
        return ResponseEntity.created(
                URI.create("/api/channel-integrations/connections/" + created.name()))
                .body(created);
    }

    @GetMapping("/connections/{name}")
    public ChannelConnectionView get(@PathVariable String name) {
        return service.get(name);
    }

    /** Admin-only runtime credential handoff for authenticated source ingestion. */
    @GetMapping("/connections/{name}/credential")
    public ChannelCredentialView credential(@PathVariable String name) {
        return service.credential(name);
    }

    @PutMapping("/connections/{name}")
    public ChannelConnectionView update(
            @PathVariable String name,
            @RequestBody ChannelConnectionUpdate update) {
        return service.update(name, update);
    }

    @PostMapping("/connections/{name}/enable")
    public ChannelConnectionView enable(@PathVariable String name) {
        return service.enable(name);
    }

    @PostMapping("/connections/{name}/disable")
    public ChannelConnectionView disable(@PathVariable String name) {
        return service.disable(name);
    }

    @PostMapping("/connections/{name}/test")
    public ChannelTestResult test(
            @PathVariable String name,
            @RequestBody ChannelTestRequest request) {
        return service.test(name, request);
    }

    @PostMapping("/connections/{name}/deliver")
    public ChannelTestResult deliver(
            @PathVariable String name,
            @RequestBody ChannelTestRequest request) {
        return service.deliver(name, request);
    }

    @PostMapping("/connections/{name}/telegram/pairings")
    public TelegramPairingStartView startTelegramPairing(@PathVariable String name) {
        return service.startTelegramPairing(name);
    }

    @GetMapping("/connections/{name}/telegram/pairings/{pairingId}")
    public TelegramPairingView telegramPairing(
            @PathVariable String name,
            @PathVariable String pairingId) {
        return service.telegramPairing(name, pairingId);
    }

    @PostMapping("/connections/{name}/telegram/pairings/{pairingId}/approve")
    public ChannelConnectionView approveTelegramPairing(
            @PathVariable String name,
            @PathVariable String pairingId,
            @RequestBody TelegramPairingApprovalRequest request) {
        return service.approveTelegramPairing(name, pairingId, request);
    }

    @DeleteMapping("/connections/{name}/telegram/pairings/{pairingId}")
    public ResponseEntity<Void> cancelTelegramPairing(
            @PathVariable String name,
            @PathVariable String pairingId) {
        service.cancelTelegramPairing(name, pairingId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/connections/{name}/telegram/diagnostics")
    public TelegramDiagnosticsView telegramDiagnostics(@PathVariable String name) {
        return service.telegramDiagnostics(name);
    }

    @GetMapping("/connections/{name}/telegram/webhook")
    public TelegramWebhookInfoView telegramWebhook(@PathVariable String name) {
        return service.telegramWebhookInfo(name);
    }

    @DeleteMapping("/connections/{name}/telegram/webhook")
    public TelegramWebhookInfoView deleteTelegramWebhook(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dropPendingUpdates) {
        return service.deleteTelegramWebhook(name, dropPendingUpdates);
    }

    @DeleteMapping("/connections/{name}")
    public ResponseEntity<Void> disconnect(@PathVariable String name) {
        service.disconnect(name);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_channel_request", error.getMessage()));
    }

    @ExceptionHandler(ChannelConnectionNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ChannelConnectionNotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("channel_connection_not_found", error.getMessage()));
    }

    @ExceptionHandler(ChannelConnectionConflictException.class)
    public ResponseEntity<ApiError> duplicate(ChannelConnectionConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("channel_connection_conflict", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("channel_state_conflict", error.getMessage()));
    }

    record ApiError(String code, String message) {
    }
}
