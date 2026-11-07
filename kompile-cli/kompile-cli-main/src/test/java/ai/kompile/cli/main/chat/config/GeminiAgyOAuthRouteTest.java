/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.NativeCliAuth;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gemini vendor's OAuth route: a built-in browser OAuth flow (Google Code
 * Assist, the gemini-cli client) stores the credential like Claude Code's
 * setup tokens, while turns ride one-shot {@code agy -p} stream-json processes
 * instead of the OpenAI-compatible API.
 */
@TemporaryUserHome
class GeminiAgyOAuthRouteTest {

    @Test
    void geminiOAuthRouteResolvesToTheAgyCliWireProtocol() {
        var config = new ChatConfig("gemini", null, "gemini-3-pro", null);
        config.setAuthenticationMethod("oauth");
        assertTrue(config.isAgyCliNative(), "gemini + oauth is the agy CLI route");
        assertFalse(config.isOpenAiCompatible(),
                "the agy route never speaks the OpenAI-compatible API");
        assertTrue(config.resolveRequestAuth() == null,
                "agy owns the Google Code Assist login; Kompile sends no credential");
        var nativeAlias = new ChatConfig("gemini", null, "gemini-3-pro", null);
        nativeAlias.setAuthenticationMethod("oauth");
        assertTrue(nativeAlias.isAgyCliNative("gemini", "native"),
                "the vendor-level check accepts the native method alias too");
        assertFalse(new ChatConfig("gemini", "key", "gemini-3-pro", null).isAgyCliNative(),
                "the API-key route stays a direct HTTP provider");
        assertTrue(new ChatConfig("gemini", "key", "gemini-3-pro", null).isOpenAiCompatible());
    }

    @Test
    void switchSupportLandsNewGeminiConfigsOnTheOauthRoute() {
        assertEquals("oauth", ChatConfig.authenticationMethodAfterProviderSwitch("gemini"),
                "a provider switch to gemini lands on its OAuth route, like Anthropic");
    }

    @Test
    void setupWizardExposesTheOauthRouteForGemini() {
        assertEquals(List.of(SetupWizard.AuthMethod.OAUTH, SetupWizard.AuthMethod.API_KEY),
                SetupWizard.authMethodsForPicker("gemini"));

        assertEquals("gemini",
                SetupWizard.resolveProviderForAuth("gemini", SetupWizard.AuthMethod.OAUTH),
                "the config keeps the vendor on the agy route");
        assertTrue(SetupWizard.isAgyCliRoute("gemini", SetupWizard.AuthMethod.OAUTH));
        assertFalse(SetupWizard.isAgyCliRoute("gemini", SetupWizard.AuthMethod.API_KEY));

        assertEquals(SetupWizard.authMethodsForPicker("gemini").size(),
                SetupWizard.authMethodsForPicker("gemini").stream().distinct().count());
        assertThrows(IllegalArgumentException.class, () ->
                        SetupWizard.resolveProviderForAuth("deepseek", SetupWizard.AuthMethod.OAUTH),
                "other OAuth-less vendors still refuse OAuth");
    }

    @Test
    void agySignInIsRegisteredAsNativeCliAuthentication() {
        assertTrue(NativeCliAuth.isSupported("agy"),
                "the agy registry entry must keep an authCommand so the sign-in menu lists it");
    }

    @Test
    void savedWizardConfigPassesStartupValidationOnTheAgyRoute() {
        // The exact save shape the wizard produces: agy's model listing is a
        // multi-vendor gateway (its Claude/GPT ids are selectable), and the
        // stored OAuth credential backs wizard surfaces only — the cli
        // transport owns the Google Code Assist login, so startup validation
        // must not demand an HTTP credential.
        var config = new ChatConfig("gemini", null, "claude-opus-4-6-thinking", null);
        config.setAuthenticationMethod("oauth");
        config.setCredentialName("default");
        config.setAuthenticationScope("session");
        config.setThinking("claude-opus-4-6-thinking");
        assertTrue(config.isValid(),
                "the agy route needs no HTTP credential: CLI login ownership is enough");

        var apiKeyRoute = new ChatConfig("gemini", null, "gemini-3.8-flash-high", null);
        apiKeyRoute.setAuthenticationMethod("api-key");
        assertFalse(apiKeyRoute.isValid(),
                "the direct HTTP route still requires a credential");
    }

    @Test
    void agyAccountStateSurfacesTheActiveAccount() {
        var state = new NativeCliAuth.AgyAccountState(true, "adam@konduit.ai");
        assertTrue(state.signedIn());
        assertEquals("adam@konduit.ai", state.summary());
        assertEquals("an Antigravity profile",
                new NativeCliAuth.AgyAccountState(true, null).summary());
        assertFalse(new NativeCliAuth.AgyAccountState(false, null).signedIn());
    }

    @Test
    void geminiHasARegisteredBrowserOauthFlowLikeClaudeAndCodex() {
        var registry = new ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry();
        assertTrue(registry.find("gemini").isPresent(),
                "the gemini vendor needs a built-in OAuth flow with a login URL");
        assertEquals("gemini", registry.oauthProviderForVendor("gemini").orElse(null));
        assertTrue(registry.find("gemini").orElseThrow().supportsApiKey(),
                "the gemini vendor also keeps its API-key route");
        assertEquals("gemini",
                SetupWizard.resolveProviderForAuth("gemini", SetupWizard.AuthMethod.OAUTH),
                "the OAuth route resolves through the registered flow");
    }

    @Test
    void discoveryReportsTheAgyEndpointOnMissingModelList() {
        var result = ModelDiscoveryHttp.agyCliResult(List.of());
        assertEquals(ModelDiscovery.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("agy"),
                "the guidance must name the agy CLI and sign-in command");
        assertFalse(result.isUsable());
    }

    @Test
    void agyRouteRejectsAttachmentsWithClearGuidance() throws Exception {
        var config = new ChatConfig("gemini", null, "gemini-3-pro", null);
        config.setAuthenticationMethod("oauth");
        DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());
        client.setOutputConsumer(ignored -> { });
        try {
            var streamAgyCli = DirectLlmClient.class.getDeclaredMethod("streamAgyCli",
                    String.class, String.class, com.fasterxml.jackson.databind.node.ArrayNode.class,
                    List.class, String.class, List.class);
            streamAgyCli.setAccessible(true);
            var result = (DirectLlmClient.StreamResult) streamAgyCli.invoke(client,
                    "look at this", null, null, List.of(), null,
                    List.of(new AttachmentInput("report.png", "image/png", true, "a".repeat(10_000), null)));
            assertTrue(result.failed, "a carry-on attachment must fail the turn at the route");
            assertTrue(result.text != null && result.text.contains("plain text"),
                    "the guidance must say the agy route takes turns as plain text");
        } finally {
            client.close();
        }
    }

    @Test
    void agyPreTurnFailureStaysRetryableAndReplaySafe() throws Exception {
        var config = new ChatConfig("gemini", null, "gemini-3-pro", null);
        config.setAuthenticationMethod("oauth");
        DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());
        client.setOutputConsumer(ignored -> { });
        try {
            var result = new DirectLlmClient.StreamResult();
            invokeRecordStreamFailure(client, result,
                    new AgyCliClient.TurnNotStartedException(
                            "The 'agy' CLI is not installed or not on PATH"));

            assertTrue(result.failed, "the turn attempt must be marked failed");
            assertTrue(readRetryable(result),
                    "a pre-turn failure must be retryable: no process ran, no prompt arrived");
            assertTrue(result.isReplaySafe(),
                    "a pre-turn failure must be replayable: the provider never saw the prompt");
        } finally {
            client.close();
        }
    }

    private static void invokeRecordStreamFailure(
            DirectLlmClient client, DirectLlmClient.StreamResult result,
            Exception failure) throws Exception {
        var method = DirectLlmClient.class.getDeclaredMethod(
                "recordStreamFailure", DirectLlmClient.StreamResult.class,
                Exception.class, String.class);
        method.setAccessible(true);
        method.invoke(client, result, failure, "[Error: ");
    }

    private static boolean readRetryable(DirectLlmClient.StreamResult result)
            throws Exception {
        var field = DirectLlmClient.StreamResult.class
                .getDeclaredField("retryableConnectivityFailure");
        field.setAccessible(true);
        return field.getBoolean(result);
    }
}
