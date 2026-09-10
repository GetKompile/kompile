/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.telegram;

import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.gateway.core.gateway.channel.TelegramApiClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived administrator-initiated Telegram pairing challenges. */
public final class TelegramPairingRegistry {

    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private final Map<String, Pairing> pairings = new ConcurrentHashMap<>();

    public TelegramPairingStartView start(String connectionName, String botUsername) {
        pairings.values().stream()
                .filter(pairing -> pairing.connectionName.equals(connectionName) && !pairing.terminal())
                .forEach(pairing -> {
                    synchronized (pairing) {
                        if (!pairing.terminal()) {
                            pairing.status = TelegramPairingView.Status.CANCELLED;
                        }
                    }
                });
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(LIFETIME);
        String command = "/pair" + (botUsername == null || botUsername.isBlank()
                ? "" : "@" + botUsername) + " " + code;
        pairings.put(id, new Pairing(id, connectionName, hash(code), expiresAt));
        return new TelegramPairingStartView(id, code, command, expiresAt);
    }

    /** Intercepts pairing commands before allowlist, graph-event, or agent dispatch. */
    public boolean intercept(
            String connectionName,
            TelegramApiClient.TelegramMessage message,
            TelegramApiClient client) {
        String text = message.text();
        if (text == null || !text.startsWith("/pair")) return false;
        String[] parts = text.trim().split("\\s+", 2);
        if (parts.length != 2 || message.chat() == null || message.from() == null) return true;
        Pairing pairing = pairings.values().stream()
                .filter(candidate -> candidate.connectionName.equals(connectionName))
                .filter(candidate -> !candidate.terminal() && !candidate.expired())
                .filter(candidate -> MessageDigest.isEqual(
                        candidate.codeHash, hash(parts[1].trim())))
                .findFirst()
                .orElse(null);
        if (pairing == null) return true;
        String chatType = message.chat().type();
        if (!("private".equals(chatType) || "group".equals(chatType)
                || "supergroup".equals(chatType))) {
            return true;
        }
        synchronized (pairing) {
            if (pairing.status != TelegramPairingView.Status.WAITING) {
                return true; // the first valid claimant owns this one-time challenge
            }
            TelegramApiClient.TelegramUser user = message.from();
            String displayName = ((user.firstName() == null ? "" : user.firstName()) + " "
                    + (user.lastName() == null ? "" : user.lastName())).trim();
            pairing.candidate = new TelegramPairingView.Candidate(
                    message.chat().id(), chatType, message.chat().title(),
                    user.id(), user.username(), displayName, Instant.now(), !"private".equals(chatType));
            pairing.status = TelegramPairingView.Status.CANDIDATE;
        }
        client.sendMessage(Long.toString(message.chat().id()),
                "Pairing request received. An administrator must approve this chat.");
        return true;
    }

    public TelegramPairingView get(String connectionName, String pairingId) {
        Pairing pairing = require(connectionName, pairingId);
        synchronized (pairing) {
            if (pairing.expired() && !pairing.terminal()) {
                pairing.status = TelegramPairingView.Status.EXPIRED;
            }
            return pairing.view();
        }
    }

    public TelegramPairingView.Candidate approve(
            String connectionName, String pairingId, long expectedChatId) {
        TelegramPairingView.Candidate candidate = candidateForApproval(
                connectionName, pairingId, expectedChatId);
        markApproved(connectionName, pairingId, expectedChatId);
        return candidate;
    }

    public TelegramPairingView.Candidate candidateForApproval(
            String connectionName, String pairingId, long expectedChatId) {
        Pairing pairing = require(connectionName, pairingId);
        synchronized (pairing) {
            validateCandidate(pairing, expectedChatId);
            return pairing.candidate;
        }
    }

    public void markApproved(String connectionName, String pairingId, long expectedChatId) {
        Pairing pairing = require(connectionName, pairingId);
        synchronized (pairing) {
            validateCandidate(pairing, expectedChatId);
            pairing.status = TelegramPairingView.Status.APPROVED;
        }
    }

    public void cancel(String connectionName, String pairingId) {
        Pairing pairing = require(connectionName, pairingId);
        synchronized (pairing) {
            if (!pairing.terminal()) pairing.status = TelegramPairingView.Status.CANCELLED;
        }
    }

    public void cancelAll(String connectionName) {
        pairings.values().stream()
                .filter(pairing -> pairing.connectionName.equals(connectionName))
                .filter(pairing -> !pairing.terminal())
                .forEach(pairing -> {
                    synchronized (pairing) {
                        if (!pairing.terminal()) {
                            pairing.status = TelegramPairingView.Status.CANCELLED;
                        }
                    }
                });
    }

    public int activeCount(String connectionName) {
        return (int) pairings.values().stream()
                .filter(pairing -> pairing.connectionName.equals(connectionName))
                .filter(pairing -> !pairing.terminal() && !pairing.expired())
                .count();
    }

    private Pairing require(String connectionName, String pairingId) {
        Pairing pairing = pairings.get(pairingId);
        if (pairing == null || !pairing.connectionName.equals(connectionName)) {
            throw new IllegalArgumentException("Telegram pairing does not exist");
        }
        return pairing;
    }

    private static void validateCandidate(Pairing pairing, long expectedChatId) {
        if (pairing.expired()) {
            pairing.status = TelegramPairingView.Status.EXPIRED;
            throw new IllegalStateException("Telegram pairing has expired");
        }
        if (pairing.status != TelegramPairingView.Status.CANDIDATE || pairing.candidate == null) {
            throw new IllegalStateException("Telegram pairing has no candidate to approve");
        }
        if (pairing.candidate.chatId() != expectedChatId) {
            throw new IllegalArgumentException(
                    "Telegram pairing candidate changed; expected chat id does not match");
        }
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class Pairing {
        private final String id;
        private final String connectionName;
        private final byte[] codeHash;
        private final Instant expiresAt;
        private volatile TelegramPairingView.Status status = TelegramPairingView.Status.WAITING;
        private volatile TelegramPairingView.Candidate candidate;

        private Pairing(String id, String connectionName, byte[] codeHash, Instant expiresAt) {
            this.id = id;
            this.connectionName = connectionName;
            this.codeHash = codeHash;
            this.expiresAt = expiresAt;
        }

        private boolean expired() { return Instant.now().isAfter(expiresAt); }
        private boolean terminal() {
            return status == TelegramPairingView.Status.APPROVED
                    || status == TelegramPairingView.Status.EXPIRED
                    || status == TelegramPairingView.Status.CANCELLED;
        }
        private TelegramPairingView view() {
            return new TelegramPairingView(id, status, candidate, expiresAt);
        }
    }
}
