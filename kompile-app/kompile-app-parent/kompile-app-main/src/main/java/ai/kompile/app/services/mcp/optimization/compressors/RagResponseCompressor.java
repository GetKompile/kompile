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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Secondary safety net for {@code rag_query} responses when {@link ai.kompile.tool.rag.RagToolImpl}
 * did not already truncate. Per-document head-then-elide truncation with a
 * result-cache handle so the agent can fetch the full list on demand.
 */
@Component
public class RagResponseCompressor implements ToolResponseCompressor {

    private static final String TOOL_NAME = "rag_query";
    private static final String RETRIEVED_DOCUMENTS = "retrieved_documents";
    private static final String TRUNCATION_MARKER = "…[truncated, full content available via fetch_result]";

    private final ResultReferenceCache resultCache;

    @Autowired
    public RagResponseCompressor(@Autowired(required = false) ResultReferenceCache resultCache) {
        this.resultCache = resultCache;
    }

    @Override
    public Set<String> supportedToolNames() {
        return Set.of(TOOL_NAME);
    }

    @Override
    public Object compress(String toolName, Object result, McpOptimizationConfig config) {
        if (!(result instanceof Map<?, ?> mapRaw)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) mapRaw);

        if (map.containsKey("result_id")) {
            return map;
        }

        Object docs = map.get(RETRIEVED_DOCUMENTS);
        if (!(docs instanceof List<?> list) || list.isEmpty()) {
            return map;
        }

        int maxChars = config.getRagMaxContentChars() != null ? config.getRagMaxContentChars() : 2000;
        if (maxChars <= 0) {
            return map;
        }

        List<Object> truncated = new ArrayList<>(list.size());
        boolean anyTruncated = false;
        for (Object doc : list) {
            if (doc instanceof CharSequence cs && cs.length() > maxChars) {
                truncated.add(cs.subSequence(0, maxChars) + TRUNCATION_MARKER);
                anyTruncated = true;
            } else {
                truncated.add(doc);
            }
        }

        if (!anyTruncated) {
            return map;
        }

        map.put(RETRIEVED_DOCUMENTS, truncated);
        if (resultCache != null) {
            String resultId = resultCache.store(result);
            map.put("result_id", resultId);
            map.put("truncated", true);
        }
        return map;
    }
}
