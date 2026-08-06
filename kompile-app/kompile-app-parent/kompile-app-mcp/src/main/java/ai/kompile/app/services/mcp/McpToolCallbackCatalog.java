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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical catalog of Spring AI tool callbacks for every MCP transport.
 *
 * <p>Spring AI's {@link MethodToolCallbackProvider} is the project-standard path for
 * deriving tool schemas and invoking {@code @Tool} methods. Its generated/AOT support
 * works in native images; hand-written record reflection does not. Building the
 * callbacks once here keeps stdio, SSE, discovery, and dynamic invocation on the same
 * native-safe contract.</p>
 */
@Component
public class McpToolCallbackCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackCatalog.class);

    private final ApplicationContext applicationContext;
    private volatile ToolCallback[] callbacks;

    public McpToolCallbackCatalog(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Return all callbacks. The returned array is a copy so transport-specific
     * filtering cannot mutate the shared catalog.
     */
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] current = callbacks;
        if (current == null) {
            synchronized (this) {
                current = callbacks;
                if (current == null) {
                    Object[] toolObjects = McpToolBeanDiscovery.discoverToolBeans(applicationContext).toArray();
                    current = MethodToolCallbackProvider.builder()
                            .toolObjects(toolObjects)
                            .build()
                            .getToolCallbacks();
                    callbacks = current;
                    log.info("Created canonical MCP callback catalog with {} tool objects and {} callbacks",
                            toolObjects.length, current.length);
                }
            }
        }
        return Arrays.copyOf(current, current.length);
    }

    public Optional<ToolCallback> findByName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(getToolCallbacks())
                .filter(callback -> callback.getToolDefinition() != null)
                .filter(callback -> toolName.equals(callback.getToolDefinition().name()))
                .findFirst();
    }

    /** Rebuild after a tool-definition change in a mutable application context. */
    public synchronized void refresh() {
        callbacks = null;
        getToolCallbacks();
    }
}
