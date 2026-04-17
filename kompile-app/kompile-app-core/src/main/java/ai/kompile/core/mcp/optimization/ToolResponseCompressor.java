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

package ai.kompile.core.mcp.optimization;

import java.util.Set;

/**
 * Compresses an oversized tool response into a smaller representation. Typical
 * strategies include head-then-elide truncation, moving bulky payloads into the
 * {@link ResultReferenceCache}, or dropping null/default fields.
 *
 * <p>Compressors are picked by tool name; a {@code "*"} wildcard represents the
 * default compressor that applies when no tool-specific one is registered.
 */
public interface ToolResponseCompressor {

    /** Wildcard name indicating this compressor is the default for unspecified tools. */
    String WILDCARD = "*";

    /**
     * The set of tool names this compressor should be invoked for, or
     * {@code {"*"}} to mark it as the default.
     */
    Set<String> supportedToolNames();

    /**
     * Compresses (or passes through) a tool invocation result. Implementations
     * MUST be fail-safe: if the compressed form ends up larger than the
     * original, return the original unchanged. Implementations must never throw
     * — on any error, return the input untouched.
     *
     * @param toolName the tool that produced this result
     * @param result   the raw tool output
     * @param config   the current optimization configuration (never null)
     * @return the (possibly compressed) tool output
     */
    Object compress(String toolName, Object result, McpOptimizationConfig config);
}
