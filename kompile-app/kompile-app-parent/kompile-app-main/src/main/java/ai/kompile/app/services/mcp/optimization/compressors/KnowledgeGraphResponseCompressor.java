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

package ai.kompile.app.services.mcp.optimization.compressors;

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.ToolResponseCompressor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge-graph specific compressor. Applies
 * {@link McpOptimizationConfig#getKnowledgeGraphTruncateChars()} to
 * {@code description} fields found in result payloads that slipped past the
 * tool-level truncation. Covers both top-level records (e.g. source context)
 * and nested list records (e.g. search results).
 */
@Component
public class KnowledgeGraphResponseCompressor implements ToolResponseCompressor {

    private static final Set<String> TOOL_NAMES = Set.of(
            "graph_search_by_entity",
            "graph_get_related_documents",
            "graph_get_source_context",
            "graph_find_connected",
            "graph_search_nodes",
            "graph_get_document_entities",
            "graph_find_by_topic",
            "graph_get_overview"
    );

    private static final Set<String> TRUNCATABLE_FIELDS = Set.of("description", "summary", "content", "text");

    @Override
    public Set<String> supportedToolNames() {
        return TOOL_NAMES;
    }

    @Override
    public Object compress(String toolName, Object result, McpOptimizationConfig config) {
        if (result == null) {
            return null;
        }
        int maxChars = config.getKnowledgeGraphTruncateChars() != null
                ? config.getKnowledgeGraphTruncateChars()
                : 200;
        if (maxChars <= 0) {
            return result;
        }
        return truncateFields(result, maxChars);
    }

    private Object truncateFields(Object node, int maxChars) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if (TRUNCATABLE_FIELDS.contains(key) && value instanceof CharSequence cs
                        && cs.length() > maxChars) {
                    out.put(key, cs.subSequence(0, maxChars - 3) + "...");
                } else {
                    out.put(key, truncateFields(value, maxChars));
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(truncateFields(item, maxChars));
            }
            return out;
        }
        return node;
    }
}
