/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

import ai.kompile.app.sync.domain.SyncProvider;

/**
 * Published after external changes have been committed to a fact sheet.
 * The application layer consumes this without coupling the facts module to the crawl implementation.
 */
public record NoteSyncPulledEvent(
        String sessionId,
        Long connectionId,
        Long factSheetId,
        SyncProvider provider,
        int pulledCount,
        int deletedCount) {
}
