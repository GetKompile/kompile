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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.project.NativeChatModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all CLI tools. Manages built-in tools and provides
 * filtered tool lists based on agent permissions.
 */
public class ToolRegistry {
    private static final Set<String> AGENT_CRAWL_LIFECYCLE_TOOLS = Set.of(
            "crawl_discover", "model_runtime", "pipeline", "crawl_documents", "crawl_source", "crawl_control", "crawl_result",
            "knowledge_status", "knowledge_search", "graph_reasoning_query",
            "ask_graph_assert", "ask_graph_retract", "activate_tools");

    // Read-only evidence consumers are shared across specialist agents. Do not add mixed-action
    // tools (knowledge_graph, code_graph, graph_embeddings): they can mutate or train models.
    private static final Set<String> AGENT_GRAPH_EVIDENCE_TOOLS = Set.of(
            "graph_search", "graph_reason", "ask_graph_query", "ask_graph_verify", "ask_graph_explain",
            "ask_graph_explain_fused", "ask_graph_synthesize", "ask_graph_claim",
            "graph_aggregate", "graph_centrality", "graph_forecast", "graph_bayes", "ask_graph_mebn");

    private final Map<String, CliTool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private static final ThreadLocal<Boolean> PRODUCT_CONSTRUCTION = ThreadLocal.withInitial(() -> false);
    private final boolean productGuards;

    /** Preserve ordinary tool identity/instanceof behavior; guard only product construction. */
    public static ToolRegistry createProduct(java.util.function.Supplier<ToolRegistry> factory) {
        boolean prior = PRODUCT_CONSTRUCTION.get();
        PRODUCT_CONSTRUCTION.set(true);
        try { return factory.get(); }
        finally {
            if (prior) PRODUCT_CONSTRUCTION.set(true);
            else PRODUCT_CONSTRUCTION.remove();
        }
    }
    private final DynamicToolManager dynamicToolManager;
    private volatile ai.kompile.cli.main.chat.agent.SubagentRunner subagentRunner;
    private volatile ai.kompile.cli.common.ProductChatPolicy productPolicy;
    private volatile Set<String> productNativeTools = Set.of();
    private volatile ProductContentSession productContentSession;
    private final Map<String, CliTool> productContentDelegates = new HashMap<>();

    public ProductContentSession productContentSession() { return productContentSession; }
    boolean hasProductConstructionGuards() { return productGuards; }

    /** Factory-only alternatives: never inherit a remote URL into content authority. */
    synchronized void registerProductContentDelegate(CliTool tool) {
        if (productPolicy != null) throw new IllegalStateException("Product registration is frozen");
        if (productGuards) productContentDelegates.put(tool.id(), tool);
    }
    private final Object productExecutions = new Object();
    private boolean productAdmission;
    private int productActiveCalls;
    // Sticky for this registry/session: local return does not establish a remote outcome.
    private boolean productUnknownOutcome;

    public boolean isProductMode() { return productPolicy != null; }

    public boolean hasProductUnknownOutcome() {
        synchronized (productExecutions) { return productUnknownOutcome; }
    }

    /** A transport failure cannot be treated as rollback, cancellation or remote completion. */
    public void markProductUnknownOutcome() {
        synchronized (productExecutions) {
            if (isProductMode()) {
                productUnknownOutcome = true;
                productAdmission = false;
            }
        }
    }

    public void openProductTurn() {
        synchronized (productExecutions) {
            if (productUnknownOutcome) throw new IllegalStateException("Product session quarantined: remote tool outcome unknown");
            if (productAdmission || productActiveCalls != 0) throw new IllegalStateException("Product tools are busy");
            productAdmission = true;
        }
    }

    public void stopProductAdmission() {
        synchronized (productExecutions) { productAdmission = false; }
    }

    /** Drain local delegate finally blocks; callers must also check hasProductUnknownOutcome(). */
    public void awaitProductQuiescence() {
        boolean interrupted = false;
        synchronized (productExecutions) {
            productAdmission = false;
            while (productActiveCalls != 0) {
                try { productExecutions.wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Freeze once, before any product model or MCP invocation. Never enables exemptions. */
    public synchronized void freezeProductPolicy(ai.kompile.cli.common.ProductChatPolicy policy,
                                                  Set<String> roleTools) {
        if (!productGuards) throw new IllegalStateException("Product policy requires a guarded registry from construction");
        if (productPolicy != null) throw new IllegalStateException("Product tool policy is already frozen");
        Set<String> approved = new HashSet<>(policy.nativeTools());
        if (roleTools != null && !roleTools.contains("*")) approved.retainAll(roleTools);
        if (policy.nativeActionPolicy() != null && approved.stream().anyMatch(id ->
                !ProductContentSession.TOOLS.contains(id) && !Set.of("mcp_tool_search", "mcp_tool_call").contains(id)))
            throw new SecurityException("Native tool has no scoped content authority");
        productNativeTools = Set.copyOf(approved);
        productContentSession = policy.nativeActionPolicy() == null ? null : new ProductContentSession(policy);
        productPolicy = Objects.requireNonNull(policy);
        for (String id : new ArrayList<>(tools.keySet())) {
            if (!approved.contains(id)) unregister(id);
            else if (productContentSession != null && ProductContentSession.TOOLS.contains(id)) {
                CliTool selected = tools.get(id);
                dynamicToolManager.register(id, selected.description(), selected.parameterSchema());
            }
        }
    }

    private boolean productAllows(String id) {
        var policy = productPolicy;
        return policy == null || productNativeTools.contains(id) || policy.isApprovedMcpTool(id);
    }

    /** Only the pinned bundle loader may add MCP tools after the native surface freezes. */
    public synchronized void registerProductMcp(CliTool tool, ai.kompile.cli.common.ProductChatPolicy policy) {
        if (productPolicy != policy || !policy.isApprovedMcpTool(tool.id()) || tools.containsKey(tool.id()))
            throw new SecurityException("MCP registration outside frozen product policy");
        putGuarded(tool);
    }

    /** Register the pinned bundle's two discovery gateways, never a generic post-freeze native tool. */
    public synchronized void registerProductGateway(CliTool tool, ai.kompile.cli.common.ProductChatPolicy policy) {
        if (productPolicy != policy || !Set.of("mcp_tool_search", "mcp_tool_call").contains(tool.id()))
            throw new SecurityException("Gateway registration outside frozen product policy");
        if (!productNativeTools.contains(tool.id())) return;
        if (tools.containsKey(tool.id())) throw new SecurityException("Product gateway identity is already registered");
        putGuarded(tool);
    }

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.productGuards = PRODUCT_CONSTRUCTION.get();
        this.dynamicToolManager = new DynamicToolManager();
    }

    /**
     * Set the subagent runner used by the task tool.
     * Allows external callers (e.g. StatusBar) to attach lifecycle listeners.
     */
    public void setSubagentRunner(ai.kompile.cli.main.chat.agent.SubagentRunner runner) {
        this.subagentRunner = runner;
    }

    /**
     * Get the subagent runner, if set.
     */
    public ai.kompile.cli.main.chat.agent.SubagentRunner getSubagentRunner() {
        return subagentRunner;
    }

    public void register(CliTool tool) {
        Objects.requireNonNull(tool, "tool");
        String id = tool.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Tool id must not be blank");
        }
        if (productPolicy != null) throw new SecurityException("Product tool registration is frozen");
        putGuarded(tool);
    }

    private void putGuarded(CliTool tool) {
        String id = tool.id();
        // Every reference handed out by this registry checks the *current* boundary.
        // Even references retained before freeze cannot execute an excluded delegate.
        CliTool guarded = !productGuards ? tool : new CliTool() {
            private CliTool effectiveDelegate() {
                return productContentSession == null ? tool : productContentDelegates.getOrDefault(id, tool);
            }
            public String id() { return id; }
            public String description() { return effectiveDelegate().description(); }
            public com.fasterxml.jackson.databind.JsonNode parameterSchema() { return effectiveDelegate().parameterSchema(); }
            public String permissionKey() { return effectiveDelegate().permissionKey(); }
            public McpToolAnnotations mcpAnnotations() { return effectiveDelegate().mcpAnnotations(); }
            public String compactHint() { return effectiveDelegate().compactHint(); }
            public boolean isBackgroundable() { return productPolicy == null && tool.isBackgroundable(); }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode params, ToolContext context)
                    throws ToolExecutionException {
                boolean product = productPolicy != null;
                synchronized (productExecutions) {
                    boolean invalidContext = context != null
                            && (context.getToolRegistry() != ToolRegistry.this || context.isAborted());
                    if (!productAllows(id) || (product && (productUnknownOutcome || !productAdmission || invalidContext)))
                        return ToolResult.error("Tool denied by frozen product policy: " + id);
                    if (product) productActiveCalls++;
                }
                try {
                    ProductContentSession content = productContentSession;
                    if (content != null && ProductContentSession.TOOLS.contains(id)) {
                        // A retained pre-freeze reader must never fall back to its ordinary (possibly
                        // remote) delegate, even in an incompletely constructed product registry.
                        if (ProductContentSession.READERS.contains(id) && !productContentDelegates.containsKey(id))
                            return ToolResult.error("Pinned local product reader is unavailable: " + id);
                        if (context == null) return ToolResult.error("Native product tools require their session context");
                        ToolContext executionScopedToolContext = context.forkForToolExecution();
                        executionScopedToolContext.linkProductManagedWork(content.tracker());
                        CliTool delegate = effectiveDelegate();
                        return content.execute(id, params, executionScopedToolContext, delegate);
                    }
                    return tool.execute(params, context);
                } finally {
                    if (product) {
                        // A synchronous probe can time out before its native HTTP/auth worker
                        // physically exits. Drain only this delegate's owner, not async jobs
                        // (owned by their tracker) or another session's native requests.
                        boolean interrupted = Thread.interrupted();
                        while (NativeChatModels.hasActiveRequest(Thread.currentThread())) {
                            try { Thread.sleep(10); }
                            catch (InterruptedException ignored) { interrupted = true; }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                        synchronized (productExecutions) {
                            productActiveCalls--;
                            productExecutions.notifyAll();
                        }
                    }
                }
            }
        };
        tools.put(id, guarded);
        dynamicToolManager.register(id, tool.description(), tool.parameterSchema());
    }

    public void unregister(String id) {
        tools.remove(id);
        dynamicToolManager.unregister(id);
    }

    public CliTool get(String id) {
        return id != null && productAllows(id) ? tools.get(id) : null;
    }

    /** Execute at the host boundary without changing tool identity or product authority. */
    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode params, ToolContext context)
            throws ToolExecutionException {
        CliTool tool = get(id);
        if (tool == null) return ToolResult.error("Unknown or unavailable tool: " + id);
        // Product personas deliberately have no generic shell execution authority.
        if (productGuards) return tool.execute(params, context);
        return ai.kompile.cli.main.chat.hooks.HookExecutor.execute(id, params, context,
                input -> tool.execute(input, context));
    }

    public Collection<CliTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get tools available to a specific agent. The agent's enabled-tools list
     * remains authoritative for ordinary tools, while the complete crawl lifecycle
     * is platform-level so every agent can start, inspect, reason over, and update
     * a crawl. Mutating calls remain subject to the permission service.
     */
    public List<CliTool> getToolsForAgent(AgentConfig agent) {
        // Remote tools remain guarded/callable through the bundle gateways, not hundreds of model schemas.
        if (productPolicy != null) return tools.values().stream().filter(tool -> productNativeTools.contains(tool.id())).toList();
        Set<String> enabled = agent.getEnabledTools();
        if (enabled == null || enabled.contains("*")) {
            return new ArrayList<>(tools.values());
        }
        List<CliTool> result = new ArrayList<>();
        for (CliTool tool : tools.values()) {
            if (enabled.contains(tool.id())
                    || AGENT_CRAWL_LIFECYCLE_TOOLS.contains(tool.id())
                    || AGENT_GRAPH_EVIDENCE_TOOLS.contains(tool.id())) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * Return only the currently active progressive tool surface, intersected with
     * the agent's normal permissions. Activation order is preserved so the small
     * model sees stable, task-oriented definitions.
     */
    public List<CliTool> getProgressiveToolsForAgent(AgentConfig agent) {
        if (productPolicy != null) return getToolsForAgent(agent);
        Map<String, CliTool> allowed = new HashMap<>();
        for (CliTool tool : getToolsForAgent(agent)) {
            allowed.put(tool.id(), tool);
        }
        List<CliTool> result = new ArrayList<>();
        for (String id : dynamicToolManager.getActiveToolIds()) {
            CliTool tool = allowed.get(id);
            if (tool != null) {
                result.add(tool);
            }
        }
        return result;
    }

    public DynamicToolManager getDynamicToolManager() {
        return dynamicToolManager;
    }

    /**
     * Seed the one capability group implied by a specialized agent role. General
     * agents stay on the core surface and discover groups through activate_tools.
     */
    public void prepareProgressiveTools(AgentConfig agent) {
        if (agent == null || agent.getName() == null) {
            return;
        }
        switch (agent.getName()) {
            case "crawler", "crawl-worker" -> {
                dynamicToolManager.activateGroup("crawl");
                dynamicToolManager.activateGroup("code");
                dynamicToolManager.activateGroup("graph_query");
                dynamicToolManager.activateGroup("graph_analysis");
            }
            case "planner" -> dynamicToolManager.activateGroup("graph_query");
            case "researcher" -> {
                dynamicToolManager.activateGroup("web");
                dynamicToolManager.activateGroup("graph_query");
            }
            case "explore-quick", "explore-deep", "code-reviewer", "architect" -> {
                dynamicToolManager.activateGroup("files");
                dynamicToolManager.activateGroup("code");
            }
            default -> {
                // The compact core plus activate_tools is intentional.
            }
        }
    }

    /**
     * Build the tools array for an LLM API call, formatted as OpenAI-compatible
     * function definitions.
     */
    public ArrayNode buildToolDefinitions(AgentConfig agent) {
        return buildToolDefinitions(getToolsForAgent(agent));
    }

    /** Build OpenAI-compatible definitions for the progressive tool surface. */
    public ArrayNode buildProgressiveToolDefinitions(AgentConfig agent) {
        return ToolSchemaOptimizer.optimize(
                buildToolDefinitions(getProgressiveToolsForAgent(agent)),
                ToolSchemaOptimizer.OptimizationLevel.MODERATE);
    }

    private ArrayNode buildToolDefinitions(List<CliTool> selectedTools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (CliTool tool : selectedTools) {
            ObjectNode toolDef = objectMapper.createObjectNode();
            toolDef.put("type", "function");
            ObjectNode function = toolDef.putObject("function");
            function.put("name", tool.id());
            function.put("description", tool.description());
            function.set("parameters", tool.parameterSchema());
            toolsArray.add(toolDef);
        }
        return toolsArray;
    }

    /**
     * Build provider-neutral definitions for DirectLlmClient. Direct provider
     * adapters translate this MCP-style shape to Responses, Chat Completions,
     * Anthropic, or Pi without losing the tool name.
     */
    public ArrayNode buildDirectToolDefinitions(AgentConfig agent) {
        return buildDirectToolDefinitions(getToolsForAgent(agent));
    }

    /** Build provider-neutral definitions for the progressive tool surface. */
    public ArrayNode buildProgressiveDirectToolDefinitions(AgentConfig agent) {
        return ToolSchemaOptimizer.optimize(
                buildDirectToolDefinitions(getProgressiveToolsForAgent(agent)),
                ToolSchemaOptimizer.OptimizationLevel.MODERATE);
    }

    private ArrayNode buildDirectToolDefinitions(List<CliTool> selectedTools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (CliTool tool : selectedTools) {
            ObjectNode toolDef = objectMapper.createObjectNode();
            toolDef.put("name", tool.id());
            toolDef.put("description", tool.description());
            toolDef.set("inputSchema", tool.parameterSchema());
            toolsArray.add(toolDef);
        }
        return toolsArray;
    }

    /**
     * Build tool descriptions for inclusion in a system prompt (for models
     * that don't support native tool calling).
     */
    public String buildToolDescriptionsText(AgentConfig agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (CliTool tool : getToolsForAgent(agent)) {
            sb.append("## ").append(tool.id()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Parameters: ").append(tool.parameterSchema().toString()).append("\n\n");
        }
        return sb.toString();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
