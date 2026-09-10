/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.core.crawl.graph;

/** Runtime-only graph gateway credentials attached by a trusted delegated-job runner. */
public record DistributedGraphRuntimeContext(
        String authorityBaseUrl,
        String bearerToken,
        String writerLease,
        String sessionId,
        String partitionId,
        int attempt) {

    public DistributedGraphRuntimeContext {
        if (authorityBaseUrl == null || authorityBaseUrl.isBlank()) {
            throw new IllegalArgumentException("authorityBaseUrl is required");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken is required");
        }
        if (writerLease == null || writerLease.isBlank()) {
            throw new IllegalArgumentException("writerLease is required");
        }
        if (sessionId == null || sessionId.isBlank() || partitionId == null || partitionId.isBlank()) {
            throw new IllegalArgumentException("session and partition identities are required");
        }
        if (attempt <= 0) throw new IllegalArgumentException("attempt must be positive");
        authorityBaseUrl = authorityBaseUrl.endsWith("/")
                ? authorityBaseUrl.substring(0, authorityBaseUrl.length() - 1) : authorityBaseUrl;
    }
}
