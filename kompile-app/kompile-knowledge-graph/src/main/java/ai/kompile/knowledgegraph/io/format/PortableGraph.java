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

import java.util.List;

/**
 * In-memory pair of node and edge collections shared by all format
 * importers/exporters.
 */
public record PortableGraph(List<PortableNode> nodes, List<PortableEdge> edges) {
    public static PortableGraph empty() {
        return new PortableGraph(List.of(), List.of());
    }
}
