/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Non-secret coordinator binding for one distributed crawl partition writing a shared graph.
 * The writer lease is transported separately in delegated-job metadata and runtime context.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DistributedGraphExecution(
        int protocolVersion,
        String sessionId,
        String partitionId,
        int partitionIndex,
        int partitionCount,
        int attempt,
        String generationOwnerId,
        Long factSheetId,
        String logicalGraphId,
        String physicalGraphId,
        String generationId,
        String expectedActivePhysicalGraphId,
        long expectedRevision,
        boolean coordinatorOwnsLifecycle,
        boolean partitionBarrierRequired) {

    public DistributedGraphExecution {
        if (protocolVersion < 1) throw new IllegalArgumentException("protocolVersion must be positive");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (partitionId == null || partitionId.isBlank()) throw new IllegalArgumentException("partitionId is required");
        if (partitionIndex < 0 || partitionCount <= 0 || partitionIndex >= partitionCount) {
            throw new IllegalArgumentException("invalid partition coordinates");
        }
        if (attempt <= 0) throw new IllegalArgumentException("attempt must be positive");
    }
}
