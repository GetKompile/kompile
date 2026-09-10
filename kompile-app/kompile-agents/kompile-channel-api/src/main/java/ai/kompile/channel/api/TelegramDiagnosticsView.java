/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.time.Instant;

/** Redacted health/checkpoint view for one Telegram connection. */
public record TelegramDiagnosticsView(
        String connectionName,
        Long botId,
        String botUsername,
        boolean pollerAlive,
        boolean ready,
        long nextOffset,
        Instant checkpointAt,
        Instant lastSuccessfulPoll,
        Instant lastUpdateAt,
        int consecutiveFailures,
        Integer lastErrorCode,
        String lastError,
        boolean webhookConfigured,
        String webhookHost,
        int pendingUpdateCount,
        int activePairings) {
}
