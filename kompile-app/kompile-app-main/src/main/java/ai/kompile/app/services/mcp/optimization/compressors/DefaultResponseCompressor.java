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
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import ai.kompile.core.mcp.optimization.ToolResponseCompressor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default compressor applied when no tool-specific compressor matches.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Drop keys whose values are {@code null}.</li>
 *   <li>Truncate any {@link Collection} / {@link List} longer than the configured
 *       threshold, replacing overflow with an {@code …truncated} marker and
 *       storing the original full list in the {@link ResultReferenceCache}.</li>
 *   <li>Truncate long strings past {@link McpOptimizationConfig#getCompressionThresholdChars()}.</li>
 *   <li>If any truncation occurred, attach a top-level {@code result_id} handle
 *       so the agent can call {@code fetch_result} to pull the full payload.</li>
 * </ol>
 */
@Component
public class DefaultResponseCompressor implements ToolResponseCompressor {

    private static final int MAX_LIST_ITEMS = 20;
    private static final String RESULT_ID_KEY = "result_id";
    private static final String TRUNCATED_KEY = "_truncated";

    private final ResultReferenceCache resultCache;

    @Autowired
    public DefaultResponseCompressor(@Autowired(required = false) ResultReferenceCache resultCache) {
        this.resultCache = resultCache;
    }

    @Override
    public Set<String> supportedToolNames() {
        return Set.of(WILDCARD);
    }

    @Override
    public Object compress(String toolName, Object result, McpOptimizationConfig config) {
        if (result == null) {
            return null;
        }
        int maxStringChars = resolveMaxChars(config);
        Compression state = new Compression();
        Object transformed = transform(result, maxStringChars, state);

        if (state.changed && transformed instanceof Map<?, ?> && resultCache != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = new LinkedHashMap<>((Map<String, Object>) transformed);
            if (!asMap.containsKey(RESULT_ID_KEY)) {
                String id = resultCache.store(result);
                asMap.put(RESULT_ID_KEY, id);
                asMap.put(TRUNCATED_KEY, true);
            }
            return asMap;
        }
        return transformed;
    }

    private Object transform(Object node, int maxStringChars, Compression state) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    state.changed = true;
                    continue;
                }
                out.put(String.valueOf(entry.getKey()), transform(value, maxStringChars, state));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            return transformList(list, maxStringChars, state);
        }
        if (node instanceof Collection<?> col) {
            return transformList(List.copyOf(col), maxStringChars, state);
        }
        if (node instanceof CharSequence cs) {
            if (maxStringChars > 0 && cs.length() > maxStringChars) {
                state.changed = true;
                return cs.subSequence(0, maxStringChars) + "…[truncated]";
            }
            return cs.toString();
        }
        return node;
    }

    private Object transformList(List<?> list, int maxStringChars, Compression state) {
        int total = list.size();
        List<Object> head = new java.util.ArrayList<>(Math.min(total, MAX_LIST_ITEMS));
        int keep = Math.min(total, MAX_LIST_ITEMS);
        for (int i = 0; i < keep; i++) {
            head.add(transform(list.get(i), maxStringChars, state));
        }
        if (total > MAX_LIST_ITEMS) {
            state.changed = true;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("items", head);
            envelope.put("returned", keep);
            envelope.put("total", total);
            envelope.put("truncated", true);
            return envelope;
        }
        return head;
    }

    private int resolveMaxChars(McpOptimizationConfig cfg) {
        Integer threshold = cfg.getCompressionThresholdChars();
        return threshold != null && threshold > 0 ? threshold : 0;
    }

    private static final class Compression {
        boolean changed = false;
    }
}
