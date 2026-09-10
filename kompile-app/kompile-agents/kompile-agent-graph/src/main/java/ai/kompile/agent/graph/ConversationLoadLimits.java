/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

/** Explicit bounds for a tail read. Older events are reported as truncated, never silently hidden. */
public record ConversationLoadLimits(int maxEvents, int maxContentBytes) {

    public static final int MAX_REQUESTED_EVENTS = 10_000;
    public static final int MAX_REQUESTED_CONTENT_BYTES = 64 * 1024 * 1024;

    public ConversationLoadLimits {
        if (maxEvents < 1 || maxEvents > MAX_REQUESTED_EVENTS) {
            throw new IllegalArgumentException(
                    "maxEvents must be between 1 and " + MAX_REQUESTED_EVENTS);
        }
        if (maxContentBytes < 1 || maxContentBytes > MAX_REQUESTED_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "maxContentBytes must be between 1 and " + MAX_REQUESTED_CONTENT_BYTES);
        }
    }
}
