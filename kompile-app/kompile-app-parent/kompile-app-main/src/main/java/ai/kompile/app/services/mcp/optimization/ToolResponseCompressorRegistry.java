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

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ToolResponseCompressor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the set of {@link ToolResponseCompressor} beans keyed by supported tool
 * name. A single {@code "*"} compressor is treated as the default and applied
 * when no tool-specific compressor matches. Callers invoke
 * {@link #compress(String, Object)} from both MCP paths.
 *
 * <p>Compression is skipped entirely when optimization is disabled, or when the
 * serialized result size falls below {@link McpOptimizationConfig#getCompressionThresholdChars()}.
 *
 * <p>Per-tool overrides ({@link McpOptimizationConfig#getToolOverrides()}) can
 * force compression on/off regardless of the global threshold.
 */
@Component
public class ToolResponseCompressorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolResponseCompressorRegistry.class);

    private final Map<String, ToolResponseCompressor> byToolName = new HashMap<>();
    private final ToolResponseCompressor defaultCompressor;
    private final McpOptimizationConfigProvider configProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public ToolResponseCompressorRegistry(
            List<ToolResponseCompressor> compressors,
            @Autowired(required = false) McpOptimizationConfigProvider configProvider,
            ObjectMapper objectMapper) {
        this.configProvider = configProvider != null
                ? configProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.objectMapper = objectMapper;

        ToolResponseCompressor discoveredDefault = null;
        for (ToolResponseCompressor compressor : compressors) {
            Set<String> names = compressor.supportedToolNames();
            if (names == null || names.isEmpty()) {
                continue;
            }
            if (names.contains(ToolResponseCompressor.WILDCARD)) {
                if (discoveredDefault != null) {
                    log.warn("Multiple default compressors found; keeping {} and ignoring {}",
                            discoveredDefault.getClass().getSimpleName(),
                            compressor.getClass().getSimpleName());
                } else {
                    discoveredDefault = compressor;
                }
            } else {
                for (String name : names) {
                    byToolName.put(name, compressor);
                }
            }
        }
        this.defaultCompressor = discoveredDefault;
        log.info("ToolResponseCompressorRegistry initialized: {} tool-specific compressors, default={}",
                byToolName.size(),
                defaultCompressor != null ? defaultCompressor.getClass().getSimpleName() : "none");
    }

    /**
     * Returns the result after applying any matching compressor. Fail-safe: if
     * compression makes the serialized payload larger, the original is
     * returned. Returns the input untouched on any error.
     */
    public Object compress(String toolName, Object result) {
        if (result == null) {
            return null;
        }

        McpOptimizationConfig cfg = configProvider.getConfiguration();
        if (!Boolean.TRUE.equals(cfg.getEnabled())) {
            return result;
        }

        McpOptimizationConfig.ToolOverride override = resolveOverride(cfg, toolName);
        if (override != null && Boolean.FALSE.equals(override.getCompressionEnabled())) {
            return result;
        }

        int originalSize = serializedSize(result);
        int thresholdChars = resolveThreshold(cfg, override);
        boolean forcedOn = override != null && Boolean.TRUE.equals(override.getCompressionEnabled());

        if (!forcedOn && originalSize < thresholdChars) {
            return result;
        }

        ToolResponseCompressor compressor = byToolName.getOrDefault(toolName, defaultCompressor);
        if (compressor == null) {
            return result;
        }

        try {
            Object compressed = compressor.compress(toolName, result, cfg);
            if (compressed == null) {
                return result;
            }
            int compressedSize = serializedSize(compressed);
            if (compressedSize >= originalSize) {
                log.debug("Compression of tool '{}' yielded {} >= {} bytes; passthrough", toolName, compressedSize, originalSize);
                return result;
            }
            log.debug("Compressed tool '{}' response: {} -> {} bytes", toolName, originalSize, compressedSize);
            return compressed;
        } catch (Exception e) {
            log.warn("Compressor {} failed for tool '{}': {} - returning original",
                    compressor.getClass().getSimpleName(), toolName, e.getMessage());
            return result;
        }
    }

    private McpOptimizationConfig.ToolOverride resolveOverride(McpOptimizationConfig cfg, String toolName) {
        Map<String, McpOptimizationConfig.ToolOverride> overrides = cfg.getToolOverrides();
        if (overrides == null || toolName == null) {
            return null;
        }
        return overrides.get(toolName);
    }

    private int resolveThreshold(McpOptimizationConfig cfg, McpOptimizationConfig.ToolOverride override) {
        if (override != null && override.getMaxResponseChars() != null && override.getMaxResponseChars() > 0) {
            return override.getMaxResponseChars();
        }
        Integer cfgThreshold = cfg.getCompressionThresholdChars();
        return cfgThreshold != null && cfgThreshold > 0 ? cfgThreshold : Integer.MAX_VALUE;
    }

    private int serializedSize(Object value) {
        if (value instanceof CharSequence cs) {
            return cs.length();
        }
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (JsonProcessingException e) {
            return 0;
        }
    }
}
