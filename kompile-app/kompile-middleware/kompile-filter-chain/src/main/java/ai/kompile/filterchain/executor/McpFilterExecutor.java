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

package ai.kompile.filterchain.executor;

import ai.kompile.core.filter.*;
import ai.kompile.filterchain.config.FilterConfig;
import ai.kompile.filterchain.config.RemoteFilterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for MCP (Model Context Protocol) remote filters.
 * Invokes MCP tools on registered MCP servers.
 * <p>
 * This executor integrates with the existing MCP infrastructure in kompile-app.
 * The MCP server must be registered and running for filters to work.
 */
@Component
public class McpFilterExecutor implements FilterExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpFilterExecutor.class);

    @Override
    public FilterResult execute(FilterConfig config, FilterContext context, FilterPhase phase) {
        RemoteFilterConfig remoteConfig = config.getRemoteConfig();
        if (remoteConfig == null) {
            return FilterResult.terminateFatalError("MCP filter missing remote configuration");
        }

        String mcpServerId = remoteConfig.getEndpoint();
        String toolName = remoteConfig.getMcpToolName();

        if (mcpServerId == null || mcpServerId.isBlank()) {
            return FilterResult.terminateFatalError("MCP filter missing server ID");
        }

        if (toolName == null || toolName.isBlank()) {
            // Default to filter ID as tool name
            toolName = config.getId();
        }

        log.debug("Executing MCP filter '{}' via tool '{}' on server '{}'",
                config.getId(), toolName, mcpServerId);

        try {
            // Build tool arguments
            Map<String, Object> toolArgs = buildToolArgs(config, context, phase);

            // TODO: Integrate with MCP client when available

            // For now, return a placeholder that indicates MCP is not yet fully integrated
            log.warn("McpFilterExecutor is not yet implemented - filter '{}' will pass through without MCP execution", config.getId());
            context.addTrace(FilterTraceEntry.warning(config.getId(),
                    "MCP filter execution not yet fully implemented"));

            return FilterResult.continueWith(context);

        } catch (Exception e) {
            log.error("MCP filter '{}' failed: {}", config.getId(), e.getMessage(), e);
            return FilterResult.terminateFatalError("MCP filter failed: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(FilterConfig config) {
        return config != null && config.getType() == FilterType.MCP;
    }

    /**
     * Build tool arguments from filter context.
     */
    private Map<String, Object> buildToolArgs(FilterConfig config, FilterContext context, FilterPhase phase) {
        Map<String, Object> args = new HashMap<>();

        // Standard arguments
        args.put("requestId", context.getRequestId());
        args.put("phase", phase.name());
        args.put("filterId", config.getId());

        // Context data
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("conversationId", context.getConversationId());
        contextData.put("userMessage", context.getUserMessage());
        contextData.put("originalQuery", context.getOriginalQuery());
        contextData.put("rewrittenQuery", context.getRewrittenQuery());
        contextData.put("formattedContext", context.getFormattedContext());
        contextData.put("llmResponse", context.getLlmResponse());

        if (context.getRequestMetadata() != null) {
            contextData.put("metadata", context.getRequestMetadata());
        }

        args.put("context", contextData);

        // Filter settings
        if (config.getSettings() != null && !config.getSettings().isEmpty()) {
            args.put("settings", config.getSettings());
        }

        return args;
    }

}
