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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Safety net for filesystem tool responses that slipped past
 * {@link ai.kompile.tool.filesystem.FilesystemToolImpl}'s inline undo pattern.
 * Replaces a still-inlined {@code previousContent} field with a cached
 * {@code undo_token}.
 *
 * <p>Also handles the {@code read_file} response: if its {@code content} is
 * over threshold, the full body is stashed in the result cache and the
 * response gets a {@code result_id} handle.
 */
@Component
public class FilesystemResponseCompressor implements ToolResponseCompressor {

    private static final String PREVIOUS_CONTENT = "previousContent";
    private static final String CONTENT = "content";

    private final ResultReferenceCache resultCache;

    @Autowired
    public FilesystemResponseCompressor(@Autowired(required = false) ResultReferenceCache resultCache) {
        this.resultCache = resultCache;
    }

    @Override
    public Set<String> supportedToolNames() {
        return Set.of(
                "write_file",
                "delete_file",
                "read_file"
        );
    }

    @Override
    public Object compress(String toolName, Object result, McpOptimizationConfig config) {
        if (!(result instanceof Map<?, ?> mapRaw)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) mapRaw);

        Object prev = map.get(PREVIOUS_CONTENT);
        if (prev instanceof String prevStr && !prevStr.isEmpty() && resultCache != null
                && !map.containsKey("undo_token")) {
            String filePath = map.get("path") instanceof String s ? s : null;
            String token = resultCache.storeFilesystemUndo(filePath, prevStr);
            map.remove(PREVIOUS_CONTENT);
            map.put("undo_token", token);
            map.put("previousContentLength", prevStr.length());
        }

        Object content = map.get(CONTENT);
        int threshold = config.getCompressionThresholdChars() != null ? config.getCompressionThresholdChars() : 4000;
        if ("read_file".equals(toolName) && content instanceof String contentStr
                && threshold > 0 && contentStr.length() > threshold
                && resultCache != null && !map.containsKey("result_id")) {
            String id = resultCache.store(result);
            map.put(CONTENT, contentStr.substring(0, threshold) + "…[truncated, fetch via fetch_result]");
            map.put("result_id", id);
            map.put("contentLength", contentStr.length());
            map.put("truncated", true);
        }

        return map;
    }
}
