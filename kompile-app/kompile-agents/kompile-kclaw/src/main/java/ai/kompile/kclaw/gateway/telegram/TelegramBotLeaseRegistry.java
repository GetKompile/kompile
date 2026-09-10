/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.telegram;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents two named runtimes from consuming the same bot-global Telegram update queue. */
public final class TelegramBotLeaseRegistry {

    private final Map<Long, Holder> leases = new ConcurrentHashMap<>();

    public synchronized Lease acquire(long botId, String connectionName) {
        Holder existing = leases.get(botId);
        if (existing != null) {
            throw new IllegalStateException(
                    "Telegram bot is already owned by connection " + existing.connectionName());
        }
        String leaseId = UUID.randomUUID().toString();
        leases.put(botId, new Holder(connectionName, leaseId));
        return new Lease(botId, leaseId);
    }

    public final class Lease implements AutoCloseable {
        private final long botId;
        private final String leaseId;
        private boolean closed;

        private Lease(long botId, String leaseId) {
            this.botId = botId;
            this.leaseId = leaseId;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            leases.computeIfPresent(botId,
                    (ignored, holder) -> holder.leaseId().equals(leaseId) ? null : holder);
        }
    }

    private record Holder(String connectionName, String leaseId) {}
}
