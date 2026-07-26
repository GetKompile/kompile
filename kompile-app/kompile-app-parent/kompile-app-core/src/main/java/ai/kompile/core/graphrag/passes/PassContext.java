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

package ai.kompile.core.graphrag.passes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version-pinned context for one decomposed extraction pass.
 *
 * <p>Every pass runs against a frozen view: the same chunk text, the same graph revision, the
 * same schema version. Two passes over the same object therefore see the same world, so their
 * outputs can be compared, cached and replayed. The {@link #pinBlock()} rendering is embedded
 * verbatim in each prompt so the model's answer is attributable to an exact state.</p>
 *
 * @param chunkId       identifier of the text chunk being processed
 * @param documentId    identifier of the source document
 * @param sourceText    the exact chunk text; evidence spans are validated against this string
 * @param graphId       graph revision the candidate context was read from
 * @param parentGraphId parent revision, when the graph is a derived snapshot
 * @param schemaVersion extraction schema version in force for this run
 * @param partitionId   entity-partition this chunk was scheduled under, when partitioned
 * @param modelId       model the pass is dispatched to, recorded for provenance
 * @param pins          additional pinned versions (rule set, ontology, profile catalog, ...)
 */
public record PassContext(
        String chunkId,
        String documentId,
        String sourceText,
        String graphId,
        String parentGraphId,
        String schemaVersion,
        String partitionId,
        String modelId,
        Map<String, String> pins) {

    public PassContext {
        sourceText = sourceText == null ? "" : sourceText;
        pins = pins == null ? Map.of() : Map.copyOf(pins);
    }

    /** Minimal context for a chunk with no graph pin — used by tests and by first-pass crawls. */
    public static PassContext forChunk(String chunkId, String documentId, String sourceText) {
        return new PassContext(chunkId, documentId, sourceText, null, null, null, null, null,
                Map.of());
    }

    /** Returns a copy with the graph revision pinned. */
    public PassContext withGraph(String graphId, String parentGraphId) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins);
    }

    /** Returns a copy with the schema version and model recorded. */
    public PassContext withSchema(String schemaVersion, String modelId) {
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, pins);
    }

    /** Returns a copy carrying an additional named version pin. */
    public PassContext withPin(String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(pins);
        merged.put(name, value);
        return new PassContext(chunkId, documentId, sourceText, graphId, parentGraphId,
                schemaVersion, partitionId, modelId, merged);
    }

    /**
     * Human-readable pin block embedded in every pass prompt. Present so the model is told
     * exactly which state it is answering against, and so a stored answer can be matched back
     * to that state during replay.
     */
    public String pinBlock() {
        StringBuilder sb = new StringBuilder("CONTEXT VERSION (fixed for this task):\n");
        appendPin(sb, "document", documentId);
        appendPin(sb, "chunk", chunkId);
        appendPin(sb, "graph", graphId);
        appendPin(sb, "parentGraph", parentGraphId);
        appendPin(sb, "schema", schemaVersion);
        appendPin(sb, "partition", partitionId);
        pins.forEach((k, v) -> appendPin(sb, k, v));
        return sb.toString();
    }

    private static void appendPin(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(name).append(": ").append(value).append('\n');
        }
    }
}
