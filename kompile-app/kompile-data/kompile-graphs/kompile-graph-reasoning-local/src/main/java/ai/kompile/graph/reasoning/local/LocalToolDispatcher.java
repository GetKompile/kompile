/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP-style tool dispatcher for local, infra-free graph reasoning.
 *
 * <p>The single entry point is {@link #dispatch(LocalReasoningSession, String, String)}, which
 * routes {@code (toolName, argsJson)} to the appropriate {@link ToolHandler} and returns a
 * JSON string result. All errors are returned as JSON — this method never throws.</p>
 *
 * <h3>Tool registration</h3>
 * <p>Handlers are registered at construction time by the four implemented handler groups:</p>
 * <ul>
 *   <li>{@link CoreHandlers} — load/save, unified query, and catalog discovery</li>
 *   <li>{@link GroundingHandlers} — KB assertion, retraction, query, verify, and explain</li>
 *   <li>{@link InferenceHandlers} — Bayesian/MEBN, claim assessment, and answer synthesis</li>
 *   <li>{@link AnalyticsHandlers} — centrality and bundled-embedding inference</li>
 * </ul>
 *
 * <h3>Error contract</h3>
 * <ul>
 *   <li>Unknown tool: {@code {"status":"INVALID","message":"...","knownTools":[...]}}</li>
 *   <li>Malformed argsJson: {@code {"status":"ERROR","message":"..."}}</li>
 *   <li>Handler exception: {@code {"status":"ERROR","message":"..."}}</li>
 * </ul>
 */
public final class LocalToolDispatcher {

    private final Map<String, ToolHandler> handlers;
    private final LocalToolCatalog catalog;

    private LocalToolDispatcher(Map<String, ToolHandler> handlers,
                                LocalToolCatalog catalog) {
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
        this.catalog = catalog;
    }

    /**
     * Build the default dispatcher with all local handler groups registered.
     *
     * @return a ready-to-use dispatcher
     */
    public static LocalToolDispatcher create() {
        Builder builder = new Builder();
        CoreHandlers core = new CoreHandlers();
        CoreHandlers.register(builder, core);
        GroundingHandlers.register(builder);
        InferenceHandlers.register(builder);
        AnalyticsHandlers.register(builder);
        LocalToolDispatcher dispatcher = builder.build();
        core.setDispatcher(dispatcher);
        return dispatcher;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    /**
     * Dispatch a tool call to the registered handler.
     *
     * @param session  the current reasoning session; must not be null
     * @param toolName the tool name to dispatch to
     * @param argsJson JSON object string containing the tool arguments; may be null or empty
     *                 (treated as an empty object)
     * @return a JSON string response (never null, never throws)
     */
    public String dispatch(LocalReasoningSession session, String toolName, String argsJson) {
        Objects.requireNonNull(session, "session must not be null");

        if (toolName == null || toolName.isBlank()) {
            return invalid("toolName must not be null or blank", knownToolsList());
        }

        ToolHandler handler = handlers.get(toolName.trim());
        if (handler == null) {
            return invalid("Unknown tool '" + toolName + "'", knownToolsList());
        }

        Map<String, Object> args;
        try {
            args = parseArgs(argsJson);
        } catch (IllegalArgumentException e) {
            return error("Failed to parse argsJson: " + e.getMessage());
        }

        try {
            return handler.handle(session, args);
        } catch (Exception e) {
            return error("Tool '" + toolName + "' failed: " + e.getMessage());
        }
    }

    // ── Catalog access ───────────────────────────────────────────────────────

    /**
     * Return the tool catalog for this dispatcher.
     * {@link CoreHandlers}'s {@code tools_catalog} handler delegates here.
     */
    public LocalToolCatalog catalog() {
        return catalog;
    }

    /**
     * Return the set of registered tool names.
     */
    public List<String> knownToolsList() {
        return new ArrayList<>(handlers.keySet());
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        String trimmed = argsJson.trim();
        if (trimmed.equals("{}") || trimmed.equals("null")) {
            return Map.of();
        }
        Object parsed = MiniJson.parse(trimmed);
        if (parsed instanceof Map<?, ?> m) {
            // Cast is safe given MiniJson always returns LinkedHashMap<String,Object> for objects
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(
                "argsJson must be a JSON object (got " + parsed.getClass().getSimpleName() + ")");
    }

    private static String invalid(String message, List<String> knownTools) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "INVALID");
        m.put("message", message);
        m.put("knownTools", knownTools);
        return MiniJson.write(m);
    }

    private static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", message);
        return MiniJson.write(m);
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    /**
     * Builder for {@link LocalToolDispatcher}. Each handler group calls
     * {@link #handler(String, LocalToolCatalog.Entry, ToolHandler)} to register its tools.
     */
    public static final class Builder {

        private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
        private final List<LocalToolCatalog.Entry> catalogEntries = new ArrayList<>();

        /**
         * Register a tool handler along with its catalog entry.
         *
         * @param name    the tool name (must be unique)
         * @param entry   catalog metadata (description + JSON Schema)
         * @param handler the handler implementation
         * @return this builder (for chaining)
         */
        public Builder handler(String name, LocalToolCatalog.Entry entry, ToolHandler handler) {
            Objects.requireNonNull(name, "tool name must not be null");
            Objects.requireNonNull(entry, "catalog entry must not be null");
            Objects.requireNonNull(handler, "handler must not be null");
            if (handlers.containsKey(name)) {
                throw new IllegalStateException("Duplicate tool registration: '" + name + "'");
            }
            handlers.put(name, handler);
            catalogEntries.add(entry);
            return this;
        }

        /** Build the dispatcher with all registered handlers. */
        LocalToolDispatcher build() {
            return new LocalToolDispatcher(handlers, new LocalToolCatalog(catalogEntries));
        }
    }
}
