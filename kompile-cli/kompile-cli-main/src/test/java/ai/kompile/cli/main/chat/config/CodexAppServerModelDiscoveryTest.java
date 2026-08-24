package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CodexAppServerModelDiscoveryTest {

    @Test
    void windowsNpmShimIsResolvedAndRunThroughCommandInterpreter(@TempDir Path tempDir)
            throws Exception {
        Path shimDirectory = Files.createDirectories(tempDir.resolve("codex & tools"));
        Path shim = shimDirectory.resolve("codex.cmd");
        Files.writeString(shim, "@echo off\r\n");

        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        "codex",
                        true,
                        shimDirectory.toString(),
                        ".EXE;.CMD;.BAT",
                        "C:\\Windows\\System32\\cmd.exe");

        assertEquals(List.of(
                "C:\\Windows\\System32\\cmd.exe",
                "/d",
                "/v:off",
                "/s",
                "/c",
                "\"%KOMPILE_CODEX_APP_SERVER_SHIM%\" app-server"),
                launch.command());
        assertEquals(shim.toAbsolutePath().normalize().toString(),
                launch.environment().get(CodexAppServerModelDiscovery.CODEX_SHIM_ENV));
        assertTrue(launch.available());
    }

    @Test
    void windowsNativeExecutableDoesNotUseCommandInterpreter(@TempDir Path tempDir)
            throws Exception {
        Path executable = tempDir.resolve("codex.exe");
        Files.writeString(executable, "fixture");

        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        "codex", true, tempDir.toString(), ".EXE;.CMD", "cmd.exe");

        assertEquals(List.of(
                executable.toAbsolutePath().normalize().toString(),
                "app-server"), launch.command());
        assertTrue(launch.environment().isEmpty());
        assertTrue(launch.available());
    }

    @Test
    void unresolvedBatchLikeNameIsNeverPassedToCommandInterpreter() {
        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        "missing.cmd&untrusted.exe", true, "", ".CMD", "cmd.exe");

        assertFalse(launch.available());
        assertTrue(launch.command().isEmpty());
        assertTrue(launch.environment().isEmpty());
        assertTrue(launch.error().contains("not found"));
    }

    @Test
    void batchShimRequiresAbsoluteWindowsCommandInterpreter(@TempDir Path tempDir)
            throws Exception {
        Path shim = tempDir.resolve("codex.cmd");
        Files.writeString(shim, "@echo off\r\n");

        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        "codex", true, tempDir.toString(), ".CMD", "cmd.exe");

        assertFalse(launch.available());
        assertTrue(launch.error().contains("ComSpec"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsBatchShimExecutesAppServerProtocolWithShellMetacharacters(
            @TempDir Path tempDir) throws Exception {
        Path shimDirectory = Files.createDirectories(
                tempDir.resolve("codex & %PATH% ^ tools"));
        Path shim = shimDirectory.resolve("codex.cmd");
        Files.writeString(shim, String.join("\r\n",
                "@echo off",
                "setlocal EnableExtensions DisableDelayedExpansion",
                "if /I not \"%~1\"==\"app-server\" exit /b 7",
                "set /p initialize=",
                "echo {\"id\":1,\"result\":{}}",
                "set /p initialized=",
                "set /p modelList=",
                "echo {\"id\":2,\"result\":{\"data\":[{\"id\":\"dynamic-windows-model\"}],\"nextCursor\":null}}",
                ""), StandardCharsets.UTF_8);

        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        shim.toString(), true, "", ".CMD", System.getenv("ComSpec"));
        ModelDiscovery.Result result = CodexAppServerModelDiscovery.discover(
                new ModelDiscovery.Context(
                        "openai-codex", null, null, null, null, Duration.ofSeconds(5)),
                launch.command(), launch.environment());

        assertEquals(ModelDiscovery.Status.SUCCESS, result.status(), result.message());
        assertEquals(List.of("dynamic-windows-model"), result.models().stream()
                .map(LiveModelDiscovery.Model::id).toList());
    }

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
    @Tag("live")
    void installedCodexReturnsItsLiveCatalogAndThinkingCapabilities() {
        ModelDiscovery.Result result = CodexAppServerModelDiscovery.discover(
                new ModelDiscovery.Context(
                        "openai-codex", null, null, null, null, Duration.ofSeconds(20)));
        assumeFalse(result.status() == ModelDiscovery.Status.UNSUPPORTED
                        && result.message().startsWith("Unable to start Codex app-server"),
                "Codex is not installed in this environment");

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

}
