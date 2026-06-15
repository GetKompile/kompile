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

package ai.kompile.app.services.mcp.optimization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link ToolCallbackProvider} decorator that runs every tool result through
 * {@link ToolResponseCompressorRegistry} before returning it to the MCP client.
 *
 * <p>The decorator is transparent: if compression is disabled or the payload is
 * smaller than the configured threshold, the original string is returned
 * unchanged.
 */
public class CompressingToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(CompressingToolCallbackProvider.class);

    private final ToolCallbackProvider delegate;
    private final ToolResponseCompressorRegistry registry;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedToolNames;

    public CompressingToolCallbackProvider(ToolCallbackProvider delegate,
                                           ToolResponseCompressorRegistry registry,
                                           ObjectMapper objectMapper) {
        this(delegate, registry, objectMapper, null);
    }

    /**
     * @param allowedToolNames when non-null, only callbacks whose tool name is
     *                         present in this set are returned. Used to
     *                         implement {@code DYNAMIC} / {@code HYBRID}
     *                         meta-tool modes without re-scanning tool beans.
     */
    public CompressingToolCallbackProvider(ToolCallbackProvider delegate,
                                           ToolResponseCompressorRegistry registry,
                                           ObjectMapper objectMapper,
                                           Set<String> allowedToolNames) {
        this.delegate = delegate;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.allowedToolNames = allowedToolNames;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] raw = delegate.getToolCallbacks();
        if (raw == null || raw.length == 0) {
            return raw;
        }
        List<ToolCallback> wrapped = new ArrayList<>(raw.length);
        for (ToolCallback cb : raw) {
            if (allowedToolNames != null && !allowedToolNames.contains(nameOf(cb))) {
                continue;
            }
            wrapped.add(new CompressingToolCallback(cb, registry, objectMapper));
        }
        return wrapped.toArray(new ToolCallback[0]);
    }

    private static String nameOf(ToolCallback cb) {
        try {
            ToolDefinition def = cb.getToolDefinition();
            return def != null ? def.name() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve tool name from callback {}", cb.getClass().getSimpleName(), e);
            return null;
        }
    }

    private static final class CompressingToolCallback implements ToolCallback {

        private final ToolCallback inner;
        private final ToolResponseCompressorRegistry registry;
        private final ObjectMapper objectMapper;

        CompressingToolCallback(ToolCallback inner,
                                ToolResponseCompressorRegistry registry,
                                ObjectMapper objectMapper) {
            this.inner = inner;
            this.registry = registry;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return inner.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return inner.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return applyCompression(inner.call(input));
        }

        @Override
        public String call(String input, ToolContext context) {
            return applyCompression(inner.call(input, context));
        }

        private String applyCompression(String rawResult) {
            if (rawResult == null || rawResult.isEmpty()) {
                return rawResult;
            }
            String toolName = safeName();
            try {
                Object parsed = objectMapper.readValue(rawResult, Object.class);
                Object compressed = registry.compress(toolName, parsed);
                if (compressed == parsed) {
                    return rawResult;
                }
                return objectMapper.writeValueAsString(compressed);
            } catch (JsonProcessingException e) {
                log.debug("Tool '{}' returned non-JSON output; skipping compression", toolName);
                return rawResult;
            } catch (Exception e) {
                log.warn("Compression failed for tool '{}': {} - returning original", toolName, e.getMessage());
                return rawResult;
            }
        }

        private String safeName() {
            try {
                ToolDefinition def = inner.getToolDefinition();
                return def != null ? def.name() : "unknown";
            } catch (Exception e) {
                return "unknown";
            }
        }
    }
}
