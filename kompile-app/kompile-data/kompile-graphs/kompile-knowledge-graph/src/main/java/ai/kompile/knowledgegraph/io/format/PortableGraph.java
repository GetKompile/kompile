/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * In-memory pair of node and edge collections shared by all format importers/exporters.
 *
 * <p>[L-8] Carries a {@code schemaVersion} envelope so an importer can detect version skew —
 * e.g. a file written by a newer build with incompatible field semantics. Files written before
 * versioning existed deserialize with {@code schemaVersion == null}, which readers treat as
 * "pre-versioning" (no warning). Bump {@link #CURRENT_SCHEMA_VERSION} only on an incompatible
 * change to the node/edge payload.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortableGraph(List<PortableNode> nodes, List<PortableEdge> edges, String schemaVersion) {

    /** Current portable-graph schema version. */
    public static final String CURRENT_SCHEMA_VERSION = "1";

    /** Stamp freshly-collected graphs with the current schema version. */
    public PortableGraph(List<PortableNode> nodes, List<PortableEdge> edges) {
        this(nodes, edges, CURRENT_SCHEMA_VERSION);
    }

    public static PortableGraph empty() {
        return new PortableGraph(List.of(), List.of());
    }
}
