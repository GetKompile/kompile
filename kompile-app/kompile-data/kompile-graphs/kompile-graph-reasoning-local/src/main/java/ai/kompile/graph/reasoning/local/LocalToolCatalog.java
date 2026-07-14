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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of all registered MCP-style tools in {@link LocalToolDispatcher}.
 *
 * <p>The catalog is always honest: only tools whose handlers are registered in the dispatcher
 * are listed. Parallel agents filling in {@link GroundingHandlers}, {@link InferenceHandlers},
 * and {@link AnalyticsHandlers} must call their group's {@code register()} method inside the
 * dispatcher constructor, after which those tools appear here automatically.</p>
 */
public final class LocalToolCatalog {

    /** A single tool entry as held in memory before serialization. */
    public record Entry(String name, String description, Map<String, Object> parameters) {}

    private final List<Entry> entries;

    LocalToolCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Return the catalog as a JSON array string. */
    public String toJson() {
        List<Object> arr = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("name", e.name());
            obj.put("description", e.description());
            obj.put("parameters", e.parameters());
            arr.add(obj);
        }
        return MiniJson.write(arr);
    }

    /** Number of registered tools. */
    public int size() {
        return entries.size();
    }

    /** All entries in registration order. */
    public List<Entry> entries() {
        return entries;
    }
}
