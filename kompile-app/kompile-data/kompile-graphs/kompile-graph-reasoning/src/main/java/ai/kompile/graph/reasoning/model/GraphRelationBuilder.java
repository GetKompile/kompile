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
package ai.kompile.graph.reasoning.model;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fluent builder for a {@link GraphRelation} (produces an immutable {@link SimpleGraphRelation}).
 * Obtain one via {@link GraphRelation#builder(String, String, String)}. Relations default to
 * directed with unit weight/confidence.
 */
public final class GraphRelationBuilder {

    private final String id;
    private final String sourceId;
    private final String targetId;
    private String type = "";
    private double weight = 1.0;
    private double confidence = 1.0;
    private boolean directed = true;
    private final Set<String> tags = new LinkedHashSet<>();
    private double[] embedding;
    private Instant timestamp;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    GraphRelationBuilder(String id, String sourceId, String targetId) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
    }

    public GraphRelationBuilder type(String type) { this.type = type; return this; }
    public GraphRelationBuilder weight(double weight) { this.weight = weight; return this; }
    public GraphRelationBuilder confidence(double confidence) { this.confidence = confidence; return this; }
    public GraphRelationBuilder directed(boolean directed) { this.directed = directed; return this; }
    public GraphRelationBuilder tag(String tag) { if (tag != null) this.tags.add(tag); return this; }
    public GraphRelationBuilder tags(Collection<String> tags) { if (tags != null) this.tags.addAll(tags); return this; }
    public GraphRelationBuilder embedding(double[] embedding) { this.embedding = embedding; return this; }
    public GraphRelationBuilder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
    public GraphRelationBuilder attribute(String key, Object value) { this.attributes.put(key, value); return this; }
    public GraphRelationBuilder attributes(Map<String, Object> attrs) { if (attrs != null) this.attributes.putAll(attrs); return this; }

    public SimpleGraphRelation build() {
        return new SimpleGraphRelation(id, sourceId, targetId, type, weight, confidence, directed,
                tags, embedding, timestamp, attributes);
    }
}
