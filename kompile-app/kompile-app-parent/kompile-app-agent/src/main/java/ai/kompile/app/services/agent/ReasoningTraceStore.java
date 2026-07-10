/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-JVM buffer for reasoning traces emitted by MCP tools during a chat turn.
 *
 * <p>Because {@code @Tool} beans execute inside the same JVM as {@link AgentChatService}
 * (the CLI subprocess calls back to the web-app's {@code /mcp/sse} endpoint),
 * we can share state via this singleton bean without cross-process plumbing.</p>
 *
 * <p>Correlation is timestamp-based: {@link AgentChatService} records
 * {@code turnStartMs} just before launching the subprocess, then calls
 * {@link #drainSince(long)} after the subprocess exits to collect all traces
 * that were pushed during that turn. This is safe for single-user scenarios;
 * for concurrent sessions the window is narrow enough (traces are pushed only
 * during subprocess execution) that interleaving is extremely unlikely.</p>
 *
 * <p>The buffer is capped at 200 entries; oldest entries are evicted when
 * the cap is exceeded to prevent unbounded growth if {@code drainSince} is
 * never called (e.g. after an error exit).</p>
 */
@Component
public class ReasoningTraceStore {

    private static final Logger log = LoggerFactory.getLogger(ReasoningTraceStore.class);
    private static final int MAX_BUFFER = 200;

    private record Entry(long timestampMs, Map<String, Object> trailDto) {}

    private final ConcurrentLinkedDeque<Entry> buffer = new ConcurrentLinkedDeque<>();

    /**
     * Push a trace DTO (field names matching the frontend {@code ReasoningTrailDto})
     * into the buffer. Thread-safe; called from MCP tool handler threads.
     */
    public void storeTrace(Map<String, Object> trailDto) {
        buffer.addLast(new Entry(System.currentTimeMillis(), trailDto));
        // Evict oldest when over cap
        while (buffer.size() > MAX_BUFFER) {
            buffer.pollFirst();
        }
        log.debug("ReasoningTraceStore: stored trace for target='{}', buffer size={}",
                trailDto.get("targetId"), buffer.size());
    }

    /**
     * Push a canonical {@link ReasoningTrace}, converting it to the frontend DTO shape via
     * {@link ReasoningTrailMapper#toTrailDto(ReasoningTrace)}. This is the entry point producers
     * (MCP tools and others) should prefer once they hold a unified trace — every reasoning kind
     * that can produce a {@code ReasoningTrace} reaches the SSE buffer through this one method.
     */
    public void storeTrace(ReasoningTrace trace) {
        storeTrace(ReasoningTrailMapper.toTrailDto(trace));
    }

    /**
     * Return a non-destructive snapshot of all currently buffered trace DTOs.
     * Unlike {@link #drainSince} this does NOT remove entries from the buffer.
     * Used by export/persistence code that needs to bundle retained traces without
     * interrupting the normal drain-after-turn flow.
     *
     * @return an immutable snapshot of all currently buffered trace DTOs (may be empty)
     */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entry e : buffer) {
            result.add(e.trailDto());
        }
        return List.copyOf(result);
    }

    /**
     * Remove and return all entries added at or after {@code epochMs}.
     * Called from the AgentChatService thread after the subprocess exits.
     */
    public List<Map<String, Object>> drainSince(long epochMs) {
        List<Map<String, Object>> result = new ArrayList<>();
        Iterator<Entry> it = buffer.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.timestampMs() >= epochMs) {
                result.add(e.trailDto());
                it.remove();
            }
        }
        if (!result.isEmpty()) {
            log.debug("ReasoningTraceStore: drained {} trace(s) since epoch {}", result.size(), epochMs);
        }
        return result;
    }
}
