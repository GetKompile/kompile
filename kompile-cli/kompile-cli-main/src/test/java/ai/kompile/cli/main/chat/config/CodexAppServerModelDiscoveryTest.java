package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "echo {\"id\":1,\"result\":{\"userAgent\":\"fixture-codex/1.2.3\"}}",
                "set /p initialized=",
                "set /p login=",
                "echo {\"id\":2,\"result\":{\"type\":\"chatgptAuthTokens\"}}",
                "set /p accountRead=",
                "echo {\"id\":3,\"result\":{\"account\":{\"type\":\"chatgpt\",\"planType\":\"plus\"}}}",
                "set /p modelList=",
                "echo {\"id\":4,\"result\":{\"data\":[{\"id\":\"dynamic-windows-model\"}],\"nextCursor\":null}}",
                ""), StandardCharsets.UTF_8);

        CodexAppServerModelDiscovery.LaunchSpec launch =
                CodexAppServerModelDiscovery.appServerCommand(
                        shim.toString(), true, "", ".CMD", System.getenv("ComSpec"));
        ModelDiscovery.Result result = CodexAppServerModelDiscovery.discover(
                oauthContext(Duration.ofSeconds(5)), launch.command(), launch.environment());

        assertEquals(ModelDiscovery.Status.SUCCESS, result.status(), result.message());
        assertEquals(List.of("dynamic-windows-model"), result.models().stream()
                .map(LiveModelDiscovery.Model::id).toList());
        assertTrue(result.message().contains("fixture-codex/1.2.3"));
        assertTrue(result.message().contains("plan plus"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void modelDiscoveryHttpPassesSelectedOauthCredentialToDynamicAppServer(
            @TempDir Path tempDir) throws Exception {
        Path shim = tempDir.resolve("codex-fixture");
        Files.writeString(shim, """
                #!/bin/sh
                read initialize
                printf '%s\\n' '{"id":1,"result":{"userAgent":"fixture-codex/next"}}'
                read initialized
                read login
                case "$login" in
                  *'"accessToken":"fixture-access-token"'*'"chatgptAccountId":"fixture-account"'*) ;;
                  *) exit 21 ;;
                esac
                printf '%s\\n' '{"id":2,"result":{"type":"chatgptAuthTokens"}}'
                read account
                printf '%s\\n' '{"id":3,"result":{"account":{"type":"chatgpt","planType":"plus"}}}'
                read models
                printf '%s\\n' '{"id":4,"result":{"data":[{"id":"gpt-6-astra"}],"nextCursor":null}}'
                """, StandardCharsets.UTF_8);
        assertTrue(shim.toFile().setExecutable(true));

        String previous = System.getProperty("kompile.codex.executable");
        ModelDiscoveryHttp.clearCache();
        try {
            System.setProperty("kompile.codex.executable", shim.toString());
            ModelDiscovery.Result result = ModelDiscoveryHttp.discoverResultWithAuth(
                    "openai-codex", oauthContext(Duration.ofSeconds(5)).auth(), null);

            assertEquals(ModelDiscovery.Status.SUCCESS, result.status(), result.message());
            assertEquals(List.of("gpt-6-astra"), result.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
            assertEquals(List.of(CodexAppServerModelDiscovery.ATTEMPTED_RESOURCE),
                    result.attemptedEndpoints());

            Files.delete(shim);
            ModelDiscovery.Result cached = ModelDiscoveryHttp.discoverResultWithAuth(
                    "openai-codex", oauthContext(Duration.ofSeconds(5)).auth(), null);
            assertEquals(ModelDiscovery.Status.SUCCESS, cached.status(), cached.message());
            assertEquals(List.of("gpt-6-astra"), cached.models().stream()
                    .map(LiveModelDiscovery.Model::id).toList());
            assertTrue(cached.message().contains("recently verified provider catalog cache"));
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.codex.executable");
            } else {
                System.setProperty("kompile.codex.executable", previous);
            }
            ModelDiscoveryHttp.clearCache();
        }
    }

    @Test
    void discoveryNeverFallsBackToAnIndependentCodexLogin() {
        ModelDiscovery.Result result = CodexAppServerModelDiscovery.discover(
                new ModelDiscovery.Context(
                        "openai-codex", null, null, null, null, Duration.ofSeconds(1)),
                List.of("a-command-that-must-not-run"));

        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, result.status());
        assertTrue(result.message().contains("selected Kompile OAuth credential"));
    }

    @Test
    void subprocessDiagnosticsRedactCredentials() {
        String diagnostic = CodexAppServerModelDiscovery.sanitizeDiagnostic(
                "Authorization: Bearer secret-value access_token=headerheaderheaderhead."
                        + "payloadpayloadpayloadpay.sigsignature refresh-token=refresh-secret");

        assertFalse(diagnostic.contains("secret-value"));
        assertFalse(diagnostic.contains("headerheader"));
        assertFalse(diagnostic.contains("refresh-secret"));
        assertTrue(diagnostic.contains("<redacted>"));
    }

    @Test
    void bundledOrCachedCatalogDiagnosticsAreNotAcceptedAsLive() {
        assertTrue(CodexAppServerModelDiscovery.usedBundledOrCachedFallback(List.of(
                "ERROR failed to refresh available models: request failed")));
        assertTrue(CodexAppServerModelDiscovery.usedBundledOrCachedFallback(List.of(
                "models cache: using cached models for OnlineIfUncached")));
        assertFalse(CodexAppServerModelDiscovery.usedBundledOrCachedFallback(List.of(
                "models cache: cache miss, fetching remote models")));
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

    private static ModelDiscovery.Context oauthContext(Duration timeout) {
        OAuthProviderFlow.RequestAuth auth = OAuthProviderFlow.RequestAuth.oauth(
                "fixture-access-token",
                "https://chatgpt.com/backend-api",
                Map.of("chatgpt-account-id", "fixture-account"));
        return new ModelDiscovery.Context(
                "openai-codex", null, null, auth, null, timeout);
    }

}
