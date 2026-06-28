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
package ai.kompile.knowledgegraph.domain;

/**
 * Read-only DTO for source weight data returned by the weights API.
 * Flattens the JPA {@link SourceWeight} entity to avoid lazy-loading and
 * circular-reference serialization issues, and projects the fields the
 * frontend {@code SourceWeight} TypeScript interface expects
 * ({@code sourceNodeId}, {@code sourceName}, {@code baseWeight}, etc.).
 */
public record SourceWeightView(
        Long id,
        String sourceNodeId,
        String sourceName,
        String sourceType,
        String topic,
        String userId,
        Double baseWeight,
        Double effectiveWeight,
        Double qualityScore,
        Double topicRelevanceScore,
        Double recencyFactor,
        Boolean enabled
) {

    /**
     * Build from a fully-loaded {@link SourceWeight} entity.
     * Must be called within an active JPA transaction so that the lazy
     * {@code sourceNode} relation can be accessed.
     */
    public static SourceWeightView from(SourceWeight sw) {
        GraphNode node = sw.getSourceNode();
        return new SourceWeightView(
                sw.getId(),
                node != null ? node.getNodeId() : null,
                node != null ? node.getTitle() : null,
                node != null ? node.getSourceType() : null,
                sw.getTopic(),
                sw.getUserId(),
                sw.getBaseWeight(),
                sw.getEffectiveWeight(),
                sw.getQualityScore(),
                sw.getTopicRelevanceScore(),
                sw.getRecencyFactor(),
                sw.getEnabled()
        );
    }

    /**
     * Create a synthetic view for a source node that has no persisted
     * {@link SourceWeight} row yet (uses the system default weight).
     */
    public static SourceWeightView defaultFor(GraphNode node, double defaultWeight) {
        return new SourceWeightView(
                null,
                node.getNodeId(),
                node.getTitle(),
                node.getSourceType(),
                null,
                null,
                defaultWeight,
                defaultWeight,
                null,
                null,
                null,
                true
        );
    }
}
