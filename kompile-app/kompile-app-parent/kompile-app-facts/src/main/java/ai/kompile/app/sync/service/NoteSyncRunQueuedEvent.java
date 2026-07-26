/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

/** Dispatched after the durable run and its per-connection lease commit. */
public record NoteSyncRunQueuedEvent(
        String sessionId,
        Long connectionId,
        boolean pullOnly) {
}
