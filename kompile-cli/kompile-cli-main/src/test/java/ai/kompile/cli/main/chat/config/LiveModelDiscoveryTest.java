package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void claudeCliCatalogParserToleratesAuthFailureNoise() {
        // Real expired-login output captured from `claude models list` — prose
        // lines never parse as model rows; grammar-matching ids do.
        List<LiveModelDiscovery.Model> models = LiveModelDiscovery.parseClaudeCliOutput("""
                "fable[1m]" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows).
                [claude-code:unrecognized_model] {"model":"fable[1m]","query_source":"sdk"}
                Failed to authenticate: OAuth session expired and could not be refreshed
                claude-sonnet-4-5
                claude-opus-4-8
                opus
                sonnet
                haiku
                fable
                claude-sonnet-5[1m]
                claude-3-7-sonnet-20250219
                """);

        assertEquals(List.of("claude-sonnet-4-5", "claude-opus-4-8", "opus",
                        "sonnet", "haiku", "fable", "claude-sonnet-5[1m]",
                        "claude-3-7-sonnet-20250219"),
                models.stream().map(LiveModelDiscovery.Model::id).toList());
    }

    @Test
    void claudeModelIdGrammarRejectsProseAndAcceptsClaudeShapes() {
        assertTrue(LiveModelDiscovery.isClaudeModelId("opus"));
        assertTrue(LiveModelDiscovery.isClaudeModelId("mythos"));
        assertTrue(LiveModelDiscovery.isClaudeModelId("claude-sonnet-4-5"));
        assertTrue(LiveModelDiscovery.isClaudeModelId("claude-3-7-sonnet-20250219"));
        assertTrue(LiveModelDiscovery.isClaudeModelId("claude-sonnet-5[1m]"));
        assertFalse(LiveModelDiscovery.isClaudeModelId("Failed to authenticate"));
        assertFalse(LiveModelDiscovery.isClaudeModelId("\"fable[1m]\" isn't described"));
        assertFalse(LiveModelDiscovery.isClaudeModelId("[claude-code:unrecognized_model]"));
        assertFalse(LiveModelDiscovery.isClaudeModelId("CLAUDE_CODE_MAX_CONTEXT_TOKENS=200000"));
        assertFalse(LiveModelDiscovery.isClaudeModelId("gpt-4o"));
    }

    @Test
    void claudeCliDiscoveryNeverReturnsEmptyForAnInstalledCli() {
        // parseClaudeCliOutput alone degrades to empty on garbage; the
        // discovery entry point layers the tier-alias floor on top so the
        // picker always has a selectable list.
        assertTrue(LiveModelDiscovery.parseClaudeCliOutput(
                "Failed to authenticate: OAuth session expired and could not be refreshed").isEmpty());
    }

    @Test
    void claudeRouteModelsApiListingKeepsPerModelEffortLevels() {
        // The Claude Code route lists models through GET /v1/models; each
        // model's capabilities.effort block is the only source of the levels
        // offered for --effort, so parsing must keep it per model.
        List<LiveModelDiscovery.Model> models = LiveModelDiscovery.parseModelsApiResponse("""
                {"data":[
                  {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5",
                   "capabilities":{"effort":{"supported":true,
                     "low":{"supported":true},"medium":{"supported":true},
                     "high":{"supported":true},"xhigh":{"supported":true},
                     "max":{"supported":true}}}},
                  {"type":"model","id":"claude-sonnet-4-6",
                   "capabilities":{"effort":{"supported":true,
                     "low":{"supported":true},"medium":{"supported":true},
                     "high":{"supported":true},"xhigh":{"supported":false},
                     "max":{"supported":true}}}},
                  {"type":"model","id":"claude-haiku-4-5",
                   "capabilities":{"effort":{"supported":false}}}
                ],"has_more":false}
                """);

        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-4-6", "claude-haiku-4-5"),
                models.stream().map(LiveModelDiscovery.Model::id).toList());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), models.get(0).variants());
        assertEquals("live:anthropic model metadata", models.get(0).capabilitySource());
        assertEquals(List.of("low", "medium", "high", "max"), models.get(1).variants());
        assertTrue(models.get(2).variants().isEmpty());
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
