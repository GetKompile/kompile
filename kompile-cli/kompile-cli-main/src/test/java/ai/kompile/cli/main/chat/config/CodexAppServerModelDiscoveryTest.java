package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
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

class CodexAppServerModelDiscoveryTest {

    @Test
    void parsesModelListEffortsDescriptionsAndDefaultsInProviderOrder() throws Exception {
        JsonNode result = ai.kompile.cli.common.util.JsonUtils.standardMapper().readTree("""
                {
                  "data": [{
                    "id": "dynamic-codex-model",
                    "defaultReasoningEffort": "medium",
                    "supportedReasoningEfforts": [
                      {"reasoningEffort": "low", "description": "Fast"},
                      {"reasoningEffort": "medium", "description": "Balanced"},
                      {"reasoningEffort": "xhigh", "description": "Deep"}
                    ]
                  }],
                  "nextCursor": null
                }
                """);

        LiveModelDiscovery.Model model =
                CodexAppServerModelDiscovery.parseModels(result).get(0);

        assertEquals("dynamic-codex-model", model.id());
        assertEquals(List.of("low", "medium", "xhigh"), model.variants());
        assertEquals("medium", model.defaultVariant());
        assertEquals("Medium — Balanced", model.variantLabels().get("medium"));
        assertEquals(CodexAppServerModelDiscovery.ATTEMPTED_RESOURCE,
                model.capabilitySource());
    }

    @Test
    void installedCodexReturnsItsLiveCatalogAndThinkingCapabilities() {
        assumeTrue(commandSucceeds("codex", "--version"),
                "Codex is not installed in this environment");

        ModelDiscovery.Result result = CodexAppServerModelDiscovery.discover(
                new ModelDiscovery.Context(
                        "openai-codex", null, null, null, null, Duration.ofSeconds(20)));

        assertEquals(ModelDiscovery.Status.SUCCESS, result.status(), result.message());
        assertFalse(result.models().isEmpty());
        LiveModelDiscovery.Model withThinking = result.models().stream()
                .filter(model -> !model.variants().isEmpty())
                .findFirst()
                .orElseThrow();
        assertFalse(withThinking.defaultVariant().isBlank());
        assertTrue(withThinking.variants().contains(withThinking.defaultVariant()));
        assertEquals(CodexAppServerModelDiscovery.ATTEMPTED_RESOURCE,
                result.attemptedEndpoints().get(0));
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
