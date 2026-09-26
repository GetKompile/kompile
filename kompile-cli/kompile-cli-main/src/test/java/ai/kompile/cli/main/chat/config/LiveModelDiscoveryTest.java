package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LiveModelDiscoveryTest {

    @Test
    void parsesOpenAiGeminiOllamaAndNestedVariantShapesWithoutAnApplicationCatalog() {
        List<LiveModelDiscovery.Model> models = LiveModelDiscovery.parseHttpModels("""
                {"data":[
                  {"id":"openai-model",
                   "thinking":{"variants":[
                     {"value":"wire-low","label":"Low","default":true},
                     {"value":"wire-high","label":"High"}]}},
                  {"name":"models/gemini-visible","baseModelId":"gemini-base"},
                  {"name":"ollama-model","capabilities":{"variants":{"fast":{}}}}
                ]}
                """);

        assertEquals(List.of("openai-model", "gemini-base", "ollama-model"),
                models.stream().map(LiveModelDiscovery.Model::id).toList());
        assertEquals(List.of("wire-low", "wire-high"), models.get(0).variants());
        assertEquals("wire-low", models.get(0).defaultVariant());
        assertEquals(List.of("Low", "High"),
                models.get(0).thinkingVariants().stream()
                        .map(LiveModelDiscovery.Variant::label).toList());
        assertEquals(List.of("fast"), models.get(2).variants());
    }

    @Test
    void parsesNativeOpenCodeModelsAndDoesNotDuplicateProviderQualifiedIds() {
        List<LiveModelDiscovery.Model> models = LiveModelDiscovery.parseNativeOutput("""
                opencode/big-pickle
                {"id":"deepseek-v4-pro","providerID":"opencode-go","variants":{"low":{},"high":{}}}
                {"id":"already/provider","providerID":"ignored","variants":{}}
                """);

        assertTrue(models.stream().anyMatch(model -> "opencode/big-pickle".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "opencode-go/deepseek-v4-pro".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "already/provider".equals(model.id())));
        assertEquals(List.of("low", "high"), models.stream()
                .filter(model -> "opencode-go/deepseek-v4-pro".equals(model.id()))
                .findFirst().orElseThrow().variants());
    }

    /** Shape of Claude Code's initialize control_response, models only (no account data). */
    private static final String CLAUDE_INITIALIZE_RESPONSE = """
            {"type":"control_response","response":{"subtype":"success","request_id":"kompile-model-discovery",
             "response":{"models":[
              {"value":"default","resolvedModel":"claude-opus-5-5","displayName":"Default (recommended)",
               "supportedEffortLevels":["low","medium","high","xhigh","max"]},
              {"value":"sonnet","resolvedModel":"claude-sonnet-5","displayName":"Sonnet 5",
               "supportedEffortLevels":["low","medium","high","xhigh","max"]},
              {"value":"haiku","resolvedModel":"claude-haiku-4-5-20251001","displayName":"Haiku 4.5"},
              {"value":"claude-sonnet-4-6","displayName":"Sonnet 4.6",
               "supportedEffortLevels":["low","medium","high","max"]},
              {"value":"claude-retired","displayName":"Retired","disabled":true},
              {"value":"","displayName":"Blank"}]}}}
            """;

    @Test
    void claudeCatalogIsClaudeCodesInitializeModelsWithPerModelEffortLevels() {
        // Rows are exactly Claude Code's: its values go to --model and its
        // supportedEffortLevels to --effort. Disabled and blank rows are dropped.
        LiveModelDiscovery.ClaudeCatalog catalog = LiveModelDiscovery.parseClaudeInitializeResponse(
                "{\"type\":\"system\",\"subtype\":\"hook_started\"}\n" + CLAUDE_INITIALIZE_RESPONSE);

        assertEquals("", catalog.error());
        assertEquals(List.of("default", "sonnet", "haiku", "claude-sonnet-4-6"),
                catalog.models().stream().map(LiveModelDiscovery.Model::id).toList());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), catalog.models().get(0).variants());
        assertEquals("native:claude initialize", catalog.models().get(0).capabilitySource());
        assertTrue(catalog.models().get(2).variants().isEmpty());
        assertEquals(List.of("low", "medium", "high", "max"), catalog.models().get(3).variants());
    }

    @Test
    void claudeCatalogFailureCarriesClaudeCodesOwnReason() {
        assertEquals("Claude Code rejected the model catalog request: Unsupported control request",
                LiveModelDiscovery.parseClaudeInitializeResponse("""
                        {"type":"control_response","response":{"subtype":"error",
                         "request_id":"kompile-model-discovery","error":"Unsupported control request"}}
                        """).error());
        LiveModelDiscovery.ClaudeCatalog prose = LiveModelDiscovery.parseClaudeInitializeResponse(
                "Warning: an older CLI\nerror: unknown option '--strict-mcp-config'\n");
        assertTrue(prose.models().isEmpty());
        assertEquals("Claude Code did not answer the model catalog request: "
                + "error: unknown option '--strict-mcp-config'", prose.error());
        assertEquals("Claude Code did not answer the model catalog request.",
                LiveModelDiscovery.parseClaudeInitializeResponse("").error());
        assertEquals("Claude Code reported no available models.",
                LiveModelDiscovery.parseClaudeInitializeResponse("""
                        {"type":"control_response","response":{"subtype":"success","response":{"models":[]}}}
                        """).error());
    }

    @Test
    void claudeCatalogCommandIsAHandshakeOnlySessionOnTheClaudeLogin() {
        List<String> command = LiveModelDiscovery.claudeCatalogCommand("claude");

        assertEquals(List.of("claude", "-p"), command.subList(0, 2));
        assertEquals("stream-json", command.get(command.indexOf("--input-format") + 1));
        assertEquals("stream-json", command.get(command.indexOf("--output-format") + 1));
        assertTrue(command.containsAll(List.of("--strict-mcp-config", "--no-session-persistence")));
        // --bare ignores the claude.ai login; `claude models ...` is not a
        // command in Claude Code, it runs a prompt turn named "models".
        assertFalse(command.contains("--bare"));
        assertFalse(command.contains("models"));
    }

    @Test
    void loggedOutClaudeCodeYieldsNoCatalogAndAnAuthNotice() {
        List<String> notices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LiveModelDiscovery.useClaudeCodeLoginProbe(
                () -> new LiveModelDiscovery.ClaudeCodeLogin(false, null, null, false));
        try {
            assertNull(LiveModelDiscovery.discoverClaudeCliModels(notices::add, errors::add));
        } finally {
            LiveModelDiscovery.useClaudeCodeLoginProbe(null);
        }
        assertEquals(List.of("claude is not logged in."), notices);
        assertTrue(errors.isEmpty());
    }

    @Test
    void claudeAuthStatusKeepsNonSecretFieldsAndReadsNoiseAsLoggedOut() {
        LiveModelDiscovery.ClaudeCodeLogin login = LiveModelDiscovery.parseClaudeAuthStatus("""
                Warning: printed before the status
                {"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","subscriptionType":"max"}
                """, true);

        assertTrue(login.loggedIn());
        assertEquals("claude.ai", login.authMethod());
        assertEquals("max", login.subscriptionType());
        assertTrue(login.describe().startsWith("Claude Code login verified (claude.ai, max subscription)"));
        assertTrue(login.describe().contains("ANTHROPIC_API_KEY is ignored here"));
        assertFalse(LiveModelDiscovery.parseClaudeAuthStatus("{\"loggedIn\":false}", false).loggedIn());
        assertFalse(LiveModelDiscovery.parseClaudeAuthStatus(
                "claude did not finish within 20 seconds.", false).loggedIn());
        assertFalse(LiveModelDiscovery.parseClaudeAuthStatus(null, false).loggedIn());
        assertTrue(LiveModelDiscovery.parseClaudeAuthStatus("", false).describe()
                .contains("Run `claude auth login`"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void nativeDiscoverySpawnSeesStdinAtEofNotAnOpenPipe() throws Exception {
        // The zero-byte sibling regression: discoverNative spawns provider model-list
        // commands through NativeCliProcess. If the spawn regresses to a default open
        // stdin pipe, Bun-based CLIs (opencode) block reading it as a prompt source
        // and discovery silently returns an empty list after its timeout. read -t
        // distinguishes a live pipe (exit > 128) from EOF (exit 1) in seconds.
        Process process = ai.kompile.cli.common.util.NativeCliProcess.processBuilder(
                List.of("/bin/sh", "-c",
                        "read -t 3 -r _; if [ $? -gt 128 ]; then echo PIPED_OPEN; "
                                + "else echo EOF_IMMEDIATE; fi"), null)
                .start();
        String output;
        try (java.io.InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "process must terminate");

        assertTrue(output.contains("EOF_IMMEDIATE") && !output.contains("PIPED_OPEN"),
                "model-list spawn must run with closed stdin, got: " + output);
    }

    @Test
    void installedOpenCodeInventoryUsesItsLiveModelsCommandWhenAvailable() {
        assumeTrue(commandSucceeds("opencode", "--version"),
                "OpenCode is not installed in this environment");

        ModelDiscovery.Result discovery =
                ModelDiscoveryHttp.discoverResult("opencode", null, null);
        assumeTrue(discovery.isUsable(),
                "OpenCode returned no accessible models: " + discovery.message());
        LiveModelDiscovery.Model withVariants = discovery.models().stream()
                .filter(model -> model.id().contains("/") && !model.variants().isEmpty())
                .findFirst()
                .orElse(null);
        assumeTrue(withVariants != null, "OpenCode returned no live thinking variants");

        assertFalse(withVariants.variants().isEmpty());
        assertEquals(withVariants.variants(),
                withVariants.thinkingVariants().stream()
                        .map(LiveModelDiscovery.Variant::value).toList());
    }

    @Test
    void codexSubscriptionCatalogUsesAppServerModelList() {
        ChatProvider codex = ChatProviderRegistry.find("openai-codex");
        assertTrue(codex != null);
        assertFalse(codex.modelDiscoveryRequiresBaseUrl());

        ModelDiscovery.Result result = codex.modelDiscoveryStrategy().discover(
                new ModelDiscovery.Context(
                        "openai-codex", null, null, null, null, Duration.ofSeconds(1)));

        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
        assertEquals(List.of(CodexAppServerModelDiscovery.ATTEMPTED_RESOURCE),
                result.attemptedEndpoints());
    }

    private static boolean commandSucceeds(String command, String... arguments) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

}
