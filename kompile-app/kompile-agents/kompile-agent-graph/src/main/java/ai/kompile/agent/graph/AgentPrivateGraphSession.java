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

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A private graph capability already bound to one trusted {@link AgentPrincipal}.
 *
 * <p>There are deliberately no owner, agent, fact-sheet, knowledge-base, or path parameters on
 * any operation. Read-context assembly can call {@link #read()} without persisting changes. Writes
 * are explicit and require the revision returned by a prior read.</p>
 */
public final class AgentPrivateGraphSession {

    private final AgentInstanceStore store;
    private final AgentInstance instance;

    AgentPrivateGraphSession(AgentInstanceStore store, AgentInstance instance) {
        this.store = Objects.requireNonNull(store, "store");
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public AgentPrincipal principal() {
        return instance.principal();
    }

    public AgentInstance instance() {
        return instance;
    }

    /** Load an independent, detached graph object. Mutating it does not persist. */
    public AgentGraphSnapshot read() throws IOException {
        return store.readGraph(principal());
    }

    /** Load a defensive copy of the exact current {@code .kgraph} archive bytes. */
    public AgentGraphArchive readArchive() throws IOException {
        return store.readArchive(principal());
    }

    /** Stream and hash the current archive without hydrating or copying its graph payload. */
    public AgentGraphRevision currentRevision() throws IOException {
        return store.currentRevision(principal());
    }

    /**
     * Apply an explicit graph mutation and atomically publish it if {@code expectedRevision} is
     * still current. The callback receives a newly loaded graph that is never shared or cached and
     * executes outside the publication lock. Concurrent candidates may therefore both run before
     * exactly one publishes; callbacks must not perform external side effects.
     */
    public AgentGraphRevision mutate(
            AgentGraphRevision expectedRevision,
            Consumer<UnifiedGraph> mutation) throws IOException {
        return store.mutate(principal(), expectedRevision, mutation);
    }

    /**
     * Validate and publish exact archive bytes if {@code expectedRevision} is still current.
     * Input accepted by this version's {@code UnifiedGraph} reader is not load-and-resaved, so all
     * accepted archive bytes (including unknown assets the current reader tolerates) remain exact.
     * Arbitrary future archive format versions are not claimed to be compatible.
     */
    public AgentGraphRevision replaceArchive(
            AgentGraphRevision expectedRevision,
            byte[] archive) throws IOException {
        return store.replaceArchive(principal(), expectedRevision, archive);
    }
}
