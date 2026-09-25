/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sink for completed tool-call usage records. Implementations persist (Task 2 journal,
 * catalog writers) or expose in-memory snapshots (Task 5 report). Failures must be
 * NONFATAL for the tool call itself: accounting failures degrade the ledger, never
 * retry or alter the tool result.
 */
public interface ToolUsageRecorder {

    /** Record one finalized invocation usage. Must not throw for I/O failure; degraded flags handle health. */
    void record(ToolCallUsage usage);

    /** Best-effort projection for reports; null/empty implementations must be tolerated. */
    ObjectNode toJsonNode(ObjectMapper mapper);
}
