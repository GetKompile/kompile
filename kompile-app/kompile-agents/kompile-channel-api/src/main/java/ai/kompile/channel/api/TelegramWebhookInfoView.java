/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Redacted Telegram webhook state; full webhook URLs are never returned. */
public record TelegramWebhookInfoView(
        boolean configured,
        String host,
        int pendingUpdateCount,
        String lastError) {
}
