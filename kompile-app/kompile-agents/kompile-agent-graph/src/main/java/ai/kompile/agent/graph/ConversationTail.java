/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import java.util.List;

/** Bounded tail read with explicit total-count and truncation evidence. */
public record ConversationTail(List<ConversationEvent> events, long totalEvents, boolean truncated) {

    public ConversationTail {
        events = events == null ? List.of() : List.copyOf(events);
        if (totalEvents < events.size()) {
            throw new IllegalArgumentException("totalEvents cannot be smaller than returned events");
        }
        if (truncated != (totalEvents > events.size())) {
            throw new IllegalArgumentException("truncated must reflect omitted events");
        }
    }

    public boolean isEmpty() {
        return totalEvents == 0;
    }
}
