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

/**
 * Read-only port into the live MCP optimization configuration.
 *
 * <p>Downstream tool modules (e.g. {@code kompile-tool-rag},
 * {@code kompile-tool-filesystem}, {@code kompile-knowledge-graph}) depend on
 * {@code kompile-app-core} only, so they inject this provider rather than the
 * full config service. A no-op implementation is provided for graceful
 * fallback when the optimization module is absent.
 */
public interface McpOptimizationConfigProvider {

    /**
     * Returns the currently active optimization configuration. Never null —
     * implementations must fall back to {@link McpOptimizationConfig#defaults()}
     * if no persisted configuration is available.
     */
    McpOptimizationConfig getConfiguration();

    /**
     * A default provider returning static defaults. Useful for tests and for
     * tool modules that want a non-null fallback when no provider bean is wired.
     */
    static McpOptimizationConfigProvider ofDefaults() {
        McpOptimizationConfig defaults = McpOptimizationConfig.defaults();
        return () -> defaults;
    }
}
