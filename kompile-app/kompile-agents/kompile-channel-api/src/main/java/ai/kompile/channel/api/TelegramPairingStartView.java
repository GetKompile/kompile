/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;

/** One-time Telegram pairing instruction; the plaintext code is returned only here. */
public record TelegramPairingStartView(
        String pairingId,
        String code,
        String command,
        Instant expiresAt) {
}
