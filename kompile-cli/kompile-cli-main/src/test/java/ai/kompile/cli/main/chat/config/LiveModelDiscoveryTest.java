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
