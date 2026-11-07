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
 * Fluent builder for a {@link GraphEntity} (produces an immutable {@link SimpleGraphEntity}).
 * Obtain one via {@link GraphEntity#builder(String)}. Every property is optional except the id;
 * unset weight/confidence default to {@code 1.0}.
 */
public final class GraphEntityBuilder {

    private final String id;
    private String type = "";
    private String label = "";
    private double weight = 1.0;
    private double confidence = 1.0;
    private final Set<String> tags = new LinkedHashSet<>();
    private double[] embedding;
    private Instant timestamp;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    GraphEntityBuilder(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public GraphEntityBuilder type(String type) { this.type = type; return this; }
    public GraphEntityBuilder label(String label) { this.label = label; return this; }
    public GraphEntityBuilder weight(double weight) { this.weight = weight; return this; }
    public GraphEntityBuilder confidence(double confidence) { this.confidence = confidence; return this; }
    public GraphEntityBuilder tag(String tag) { if (tag != null) this.tags.add(tag); return this; }
    public GraphEntityBuilder tags(Collection<String> tags) { if (tags != null) this.tags.addAll(tags); return this; }
    public GraphEntityBuilder embedding(double[] embedding) { this.embedding = embedding; return this; }
    public GraphEntityBuilder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
    public GraphEntityBuilder attribute(String key, Object value) { this.attributes.put(key, value); return this; }
    public GraphEntityBuilder attributes(Map<String, Object> attrs) { if (attrs != null) this.attributes.putAll(attrs); return this; }

    public SimpleGraphEntity build() {
        return new SimpleGraphEntity(id, type, label, weight, confidence, tags, embedding, timestamp, attributes);
    }
}
