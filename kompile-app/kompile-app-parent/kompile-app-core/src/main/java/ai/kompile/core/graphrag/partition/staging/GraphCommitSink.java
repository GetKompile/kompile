/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.PartitionKey;

import java.util.List;

/**
 * Where a staged graph goes when the partition is done with it.
 *
 * <p>This is the seam between the partition's reasoning and whatever actually stores a graph. It
 * takes a whole merged graph rather than a stream of nodes and edges because that is the unit
 * staging exists to produce: by the time a sink is called, every duplicate has been folded and
 * every disagreement resolved, so the store has nothing left to decide.</p>
 */
@FunctionalInterface
public interface GraphCommitSink {

    /**
     * Writes {@code graph} on behalf of {@code key}.
     *
     * <p>Implementations should report failure through {@link CommitOutcome#problems()} rather
     * than by throwing when the failure is partial — half a graph written is a fact the caller
     * needs, and an exception loses the counts that say how far it got.</p>
     */
    CommitOutcome commit(PartitionKey key, Graph graph);

    /** A sink that stores nothing and says so; useful for a dry run. */
    static GraphCommitSink discarding() {
        return (key, graph) -> new CommitOutcome(0, 0, List.of("commit sink discards"));
    }

    /**
     * What a sink managed to write.
     *
     * @param entities      entities the store accepted
     * @param relationships relationships the store accepted
     * @param problems      anything that did not get written, and why
     */
    record CommitOutcome(int entities, int relationships, List<String> problems) {

        public CommitOutcome {
            entities = Math.max(0, entities);
            relationships = Math.max(0, relationships);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static CommitOutcome of(int entities, int relationships) {
            return new CommitOutcome(entities, relationships, List.of());
        }

        public static CommitOutcome nothing() {
            return new CommitOutcome(0, 0, List.of());
        }

        public boolean isClean() {
            return problems.isEmpty();
        }

        public String describe() {
            return entities + " entities, " + relationships + " relationships"
                    + (problems.isEmpty() ? "" : ", problems: " + problems);
        }
    }
}
