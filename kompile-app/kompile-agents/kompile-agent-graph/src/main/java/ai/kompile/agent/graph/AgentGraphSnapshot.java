/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.agent.graph;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.Objects;

/** A detached mutable graph value together with the revision from which it was loaded. */
public record AgentGraphSnapshot(UnifiedGraph graph, AgentGraphRevision revision) {

    public AgentGraphSnapshot {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(revision, "revision");
    }
}
