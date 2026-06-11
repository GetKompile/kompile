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

package ai.kompile.core.graphrag.query;

import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the output of a query from the Graph RAG system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagResult {
    /**
     * The generated, synthesized answer to the user's query.
     */
    private String answer;

    /**
     * The formatted context string, derived from the graph, that was used to generate the answer.
     * This can be used for transparency and debugging.
     */
    private String formattedContext;

    /**
     * Entities found during graph traversal.
     */
    private List<Entity> entities;

    /**
     * Relationships found during graph traversal.
     */
    private List<Relationship> relationships;

    /**
     * Communities identified in the result graph.
     */
    private List<Community> communities;

    /**
     * Source text chunks that contributed to the answer.
     */
    private List<String> sourceChunks;
}