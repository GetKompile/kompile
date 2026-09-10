/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;

/** Secret-free state of a Telegram pairing request. */
public record TelegramPairingView(
        String pairingId,
        Status status,
        Candidate candidate,
        Instant expiresAt) {

    public enum Status { WAITING, CANDIDATE, APPROVED, EXPIRED, CANCELLED }

    public record Candidate(
            long chatId,
            String chatType,
            String chatTitle,
            long userId,
            String username,
            String displayName,
            Instant observedAt,
            boolean authorizesEntireChat) {
    }
}
