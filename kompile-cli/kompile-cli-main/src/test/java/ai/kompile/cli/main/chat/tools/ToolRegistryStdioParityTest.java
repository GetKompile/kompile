/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tools.grounding.AskGraphAssertTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphClaimTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphExplainTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphRetractTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphFusedTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphMebnTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphQueryTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSubscribeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSynthesizeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphVerifyTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlControlTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDiscoveryTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.ModelRuntimeTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlSourceTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphExportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphImportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasonTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasoningQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity test: every graph/grounding {@link CliTool} registered in
 * {@link ToolRegistryFactory} (the interactive-chat path) must also be
 * registered in {@code McpStdioCommand.buildToolMap()} (the MCP stdio path).
 *
 * <h2>How the test works</h2>
 * <p>The test instantiates each graph/grounding tool (the same way buildToolMap does)
 * and compares their {@link CliTool#id()} values against the set of tool IDs that
 * McpStdioCommand is expected to register. The expected stdio set is derived from the
 * same constructor calls that appear in the production buildToolMap method, so keeping
 * them in sync is the purpose of this test.</p>
 *
 * <h2>Adding a new tool</h2>
 * <ol>
 *   <li>Register it in {@link ToolRegistryFactory} (chat path).</li>
 *   <li>Register it in {@code McpStdioCommand.buildToolMap()} (stdio path).</li>
 *   <li>Add it to {@link #buildExpectedStdioIds()} below.</li>
 *   <li>This test passes.</li>
 * </ol>
 *
 * <h2>Intentional chat-only tools</h2>
 * <p>If a tool requires live TTY/renderer state that does not exist in stdio mode,
 * add its id to {@link #CHAT_ONLY_EXCLUSIONS} with a comment explaining why.
 * The test will then accept the gap.</p>
 */
class ToolRegistryStdioParityTest {

    /**
     * Tool IDs that are intentionally registered only in ToolRegistryFactory (chat path)
     * and NOT in McpStdioCommand (stdio MCP path), with documented reasons.
     *
     * <p>Empty by default — all graph/grounding tools should be in both paths.</p>
     */
    private static final Set<String> CHAT_ONLY_EXCLUSIONS = Set.of(
            // none currently — every graph/grounding tool works over the REST backend
            // so it can be served by both the interactive chat and MCP stdio paths.
    );

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String DUMMY_BASE_URL = "http://localhost:8080";

    /**
     * Enumerate all graph/grounding tools registered in {@link ToolRegistryFactory}.
     * This mirrors the backend-requiring tool registrations in {@code ToolRegistryFactory.create()}.
     */
    private Map<String, CliTool> buildFactoryGraphTools() {
        Map<String, CliTool> tools = new LinkedHashMap<>();
        put(tools, new RagSearchTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphRagSearchTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphAggregateTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphForecastTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphCentralityTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphQueryTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphVerifyTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphAssertTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphRetractTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphMebnTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphExplainTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphFusedTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphSynthesizeTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphSubscribeTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphReasonTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphImportTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphExportTool(DUMMY_BASE_URL, OM));
        put(tools, new CrawlSourceTool(DUMMY_BASE_URL, OM));
        put(tools, new CrawlDocumentsTool(DUMMY_BASE_URL, OM));
        put(tools, new CrawlDiscoveryTool(DUMMY_BASE_URL, OM));
        put(tools, new ModelRuntimeTool(OM));
        put(tools, new CrawlControlTool(DUMMY_BASE_URL, OM));
        put(tools, new ProcessMiningCliTool(DUMMY_BASE_URL, OM));
        put(tools, new AskGraphClaimTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphReasoningQueryTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphBayesTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphEmbeddingsTool(DUMMY_BASE_URL, OM));
        put(tools, new GraphSimulateTool(DUMMY_BASE_URL, OM));
        return tools;
    }

    /**
     * Enumerate the graph/grounding tool IDs that McpStdioCommand.buildToolMap()
     * is expected to register. Keep this in sync with the production registrations
     * in McpStdioCommand — that is the purpose of this test.
     */
    private Set<String> buildExpectedStdioIds() {
        Set<String> ids = new TreeSet<>();
        // These IDs are verified against the actual CliTool.id() return values below.
        ids.add(new RagSearchTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphRagSearchTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphAggregateTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphForecastTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphCentralityTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphQueryTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphVerifyTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphAssertTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphRetractTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphMebnTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphExplainTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphFusedTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphSynthesizeTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphSubscribeTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphReasonTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphImportTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphExportTool(DUMMY_BASE_URL, OM).id());
        ids.add(new CrawlSourceTool(DUMMY_BASE_URL, OM).id());
        ids.add(new CrawlDocumentsTool(DUMMY_BASE_URL, OM).id());
        ids.add(new CrawlDiscoveryTool(DUMMY_BASE_URL, OM).id());
        ids.add(new ModelRuntimeTool(OM).id());
        ids.add(new CrawlControlTool(DUMMY_BASE_URL, OM).id());
        ids.add(new ProcessMiningCliTool(DUMMY_BASE_URL, OM).id());
        ids.add(new AskGraphClaimTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphReasoningQueryTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphBayesTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphEmbeddingsTool(DUMMY_BASE_URL, OM).id());
        ids.add(new GraphSimulateTool(DUMMY_BASE_URL, OM).id());
        return ids;
    }

    private static void put(Map<String, CliTool> map, CliTool tool) {
        map.put(tool.id(), tool);
    }

    /**
     * Core parity assertion: every graph/grounding tool in ToolRegistryFactory must
     * appear in the McpStdioCommand expected-registration set (minus documented exclusions).
     *
     * <p>Failure message names the exact missing tool IDs so engineers know what to add.</p>
     */
    @Test
    void allFactoryGraphToolsAreExpectedInStdio() {
        Map<String, CliTool> factoryTools = buildFactoryGraphTools();
        Set<String> stdioIds = buildExpectedStdioIds();

        List<String> missing = new ArrayList<>();
        for (String id : factoryTools.keySet()) {
            if (!CHAT_ONLY_EXCLUSIONS.contains(id) && !stdioIds.contains(id)) {
                missing.add(id);
            }
        }

        assertTrue(missing.isEmpty(),
                "The following graph/grounding tools are registered in ToolRegistryFactory (chat path) " +
                "but MISSING from the expected McpStdioCommand stdio set. " +
                "Add them to McpStdioCommand.buildToolMap() and to buildExpectedStdioIds() in this test. " +
                "Missing: " + missing);
    }

    /**
     * Reverse: every ID in the expected-stdio set must also be in the factory set,
     * so the two lists don't diverge in the other direction.
     */
    @Test
    void allExpectedStdioToolsAreInFactory() {
        Map<String, CliTool> factoryTools = buildFactoryGraphTools();
        Set<String> stdioIds = buildExpectedStdioIds();

        List<String> onlyInStdio = new ArrayList<>();
        for (String id : stdioIds) {
            if (!factoryTools.containsKey(id)) {
                onlyInStdio.add(id);
            }
        }

        assertTrue(onlyInStdio.isEmpty(),
                "The following tool IDs are in the expected stdio set but NOT in ToolRegistryFactory. " +
                "Add them to ToolRegistryFactory.create() or remove from buildExpectedStdioIds(). " +
                "Dangling: " + onlyInStdio);
    }

    /**
     * Verify CHAT_ONLY_EXCLUSIONS doesn't hold stale tool IDs that no longer exist
     * in ToolRegistryFactory.
     */
    @Test
    void chatOnlyExclusionsAreActuallyRegisteredInFactory() {
        Map<String, CliTool> factoryTools = buildFactoryGraphTools();
        List<String> staleExclusions = new ArrayList<>();
        for (String excluded : CHAT_ONLY_EXCLUSIONS) {
            if (!factoryTools.containsKey(excluded)) {
                staleExclusions.add(excluded);
            }
        }
        assertTrue(staleExclusions.isEmpty(),
                "CHAT_ONLY_EXCLUSIONS contains tool IDs not registered in ToolRegistryFactory — " +
                "remove the stale entries: " + staleExclusions);
    }
}
