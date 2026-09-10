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

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asserts that the local dispatcher's catalog stays in sync with the server-side
 * CliTool definitions for tools that BOTH sides expose.
 *
 * <p>The server-side required-parameter sets are pinned here as constants rather than
 * depending on a Spring Boot classpath. When server-side tool signatures change,
 * this test will fail with a clear drift message.
 *
 * <p>Server reference: kompile-middleware/kompile-tools/kompile-tool-graph/
 *   ai.kompile.tool.graph.GraphReasoningQueryTool  — tool name "graph_reasoning_query"
 *   Required params: none; operation defaults from queryText or to CAPABILITIES
 *
 * <p>The local catalog stores required params inside the JSON-Schema parameters map under
 * the "required" key (a {@code List<String>}), as set by
 * {@code CoreHandlers.schemaFor(name, description, required, properties)}.
 *
 * <p>Update this file whenever the server-side tool definition changes.
 * Last synced: 2026-07-12
 */
class ToolCatalogParityTest {

    /**
     * Server-side schema pins for shared tools.
     * Key = tool name, Value = set of required parameter names as declared in the server tool.
     *
     * Source: GraphReasoningQueryTool.QueryInput @ToolParam fields with non-null semantics.
     * The server QueryInput marks operation optional and delegates defaulting to the service.
     */
    private static final Map<String, Set<String>> SERVER_REQUIRED_PARAMS = Map.of(
            "graph_reasoning_query", Set.of()
    );

    @Test
    void sharedToolsExistInLocalDispatcher() {
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();
        List<String> localTools = dispatcher.knownToolsList();

        List<String> missing = new ArrayList<>();
        for (String toolName : SERVER_REQUIRED_PARAMS.keySet()) {
            if (!localTools.contains(toolName)) {
                missing.add(toolName);
            }
        }
        assertTrue(missing.isEmpty(),
                "Tools present on server but MISSING from local dispatcher: " + missing +
                "\nLocal dispatcher has: " + localTools);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedToolsHaveMatchingRequiredParams() {
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();
        LocalToolCatalog catalog = dispatcher.catalog();

        List<String> driftMessages = new ArrayList<>();

        for (Map.Entry<String, Set<String>> serverEntry : SERVER_REQUIRED_PARAMS.entrySet()) {
            String toolName = serverEntry.getKey();
            Set<String> serverRequired = serverEntry.getValue();

            LocalToolCatalog.Entry localEntry = catalog.entries().stream()
                    .filter(e -> e.name().equals(toolName))
                    .findFirst()
                    .orElse(null);

            if (localEntry == null) {
                driftMessages.add("Tool '" + toolName + "' not found in local catalog");
                continue;
            }

            // Required params are stored under the "required" key in the JSON-Schema parameters map.
            // Value is a List<String> placed there by CoreHandlers.schemaFor().
            Object requiredRaw = localEntry.parameters().get("required");
            Set<String> localRequired = new HashSet<>();
            if (requiredRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        localRequired.add(s);
                    }
                }
            }

            if (!serverRequired.equals(localRequired)) {
                driftMessages.add("Tool '" + toolName + "' required params drift: " +
                        "server requires " + serverRequired +
                        " but local has " + localRequired);
            }
        }

        assertTrue(driftMessages.isEmpty(),
                "Catalog parity drift detected:\n" + String.join("\n", driftMessages));
    }
}
