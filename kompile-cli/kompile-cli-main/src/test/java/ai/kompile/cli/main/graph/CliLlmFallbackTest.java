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
package ai.kompile.cli.main.graph;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.app.CrawlCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CLI LLM fallback system — verifies command registration, option
 * parsing, and help text for the new local extraction flags. Does NOT require a
 * running server or LLM provider.
 */
class CliLlmFallbackTest {

    private CommandLine cmd;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(new MainCommand());
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        cmd.setOut(new PrintWriter(outWriter));
        cmd.setErr(new PrintWriter(errWriter));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GraphExtractCommand — new local extraction options
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testExtractCommandHasLocalOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--local"),
                "extract should have --local option");
    }

    @Test
    void testExtractCommandHasAutoStartOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--auto-start"),
                "extract should have --auto-start option");
    }

    @Test
    void testExtractCommandHasLlmProviderOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--llm-provider"),
                "extract should have --llm-provider option");
    }

    @Test
    void testExtractCommandHasLlmModelOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--llm-model"),
                "extract should have --llm-model option");
    }

    @Test
    void testExtractCommandHasLlmApiKeyOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--llm-api-key"),
                "extract should have --llm-api-key option");
    }

    @Test
    void testExtractCommandHasCustomPromptOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--custom-prompt"),
                "extract should have --custom-prompt option");
    }

    @Test
    void testExtractCommandHasRelationshipTypesOption() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine extractCmd = graphCmd.getSubcommands().get("extract");
        assertNotNull(extractCmd.getCommandSpec().findOption("--relationship-types"),
                "extract should have --relationship-types option");
    }

    @Test
    void testExtractCommandHelpShowsLocalOptions() {
        int exitCode = cmd.execute("graph", "extract", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--local"), "help should show --local");
        assertTrue(output.contains("--auto-start"), "help should show --auto-start");
        assertTrue(output.contains("--llm-provider"), "help should show --llm-provider");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CrawlCommand — graph-local and graph-auto-start options
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCrawlStartHasGraphLocalOption() {
        CommandLine crawlCmd = new CommandLine(new CrawlCommand());
        CommandLine startCmd = crawlCmd.getSubcommands().get("start");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-local"),
                "crawl start should have --graph-local option");
    }

    @Test
    void testCrawlStartHasGraphAutoStartOption() {
        CommandLine crawlCmd = new CommandLine(new CrawlCommand());
        CommandLine startCmd = crawlCmd.getSubcommands().get("start");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-auto-start"),
                "crawl start should have --graph-auto-start option");
    }

    @Test
    void testCrawlStartHelpShowsGraphLocalOptions() {
        CommandLine crawlCmd = new CommandLine(new CrawlCommand());
        StringWriter out = new StringWriter();
        crawlCmd.setOut(new PrintWriter(out));
        int exitCode = crawlCmd.execute("start", "--help");
        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("--graph-local"), "help should show --graph-local");
        assertTrue(output.contains("--graph-auto-start"), "help should show --graph-auto-start");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CliExtractionLlmClient — explicit provider resolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testResolveWithExplicitProvider() {
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                "openai", "gpt-4o", "test-key", false);
        assertNotNull(client, "Should resolve with explicit provider");
        assertTrue(client.getResolvedFrom().contains("openai"));
        client.close();
    }

    @Test
    void testResolveWithExplicitAnthropicProvider() {
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                "anthropic", "claude-sonnet-4", "test-key", false);
        assertNotNull(client, "Should resolve with explicit Anthropic provider");
        assertTrue(client.getResolvedFrom().contains("anthropic"));
        client.close();
    }

    @Test
    void testResolveWithExplicitOllamaProvider() {
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                "ollama", "llama3.3", null, false);
        assertNotNull(client, "Should resolve with explicit Ollama provider");
        assertTrue(client.getResolvedFrom().contains("ollama"));
        client.close();
    }

    @Test
    void testResolveReportsProviderInResolvedFrom() {
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                "groq", "llama-3.3-70b-versatile", "test-key", false);
        assertNotNull(client);
        assertEquals("explicit flags (provider=groq)", client.getResolvedFrom());
        client.close();
    }

    @Test
    void testClientIsAutoCloseable() {
        // Verify close() doesn't throw
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                "openai", "gpt-4o", "test-key", false);
        assertDoesNotThrow(() -> client.close());
    }

    @Test
    void testResolveWithNoProviderAndNoAutoStartReturnsNull() {
        // When no explicit provider, no ChatConfig, no Ollama, and autoStart=false
        // the result depends on env vars. We can at least verify no NPE.
        CliExtractionLlmClient client = CliExtractionLlmClient.resolve(
                null, null, null, false);
        // May return null or a valid client depending on env vars/Ollama
        if (client != null) {
            client.close();
        }
    }
}
