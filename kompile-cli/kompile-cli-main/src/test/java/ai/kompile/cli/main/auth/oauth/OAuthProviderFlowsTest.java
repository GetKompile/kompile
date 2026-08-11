package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OAuthProviderFlowsTest {
    private static final OAuthProviderFlow.LoginOptions MANUAL_BROWSER =
            new OAuthProviderFlow.LoginOptions("browser", true, null, null);

    @Test
    void openAiCodexBrowserPkceExchangesCodeAndExtractsAccount() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"%s","refresh_token":"refresh","expires_in":3600}
                """.formatted(openAiJwt("account-123"))));
        TestInteraction interaction = new TestInteraction("manual-code");
        OpenAiCodexOAuthFlow flow = new OpenAiCodexOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("account-123", credential.getMetadata("accountId"));
        assertTrue(interaction.authorizationUri.toString().contains("code_challenge="));
        assertTrue(transport.requests.get(0).body().contains("code=manual-code"));
        OAuthProviderFlow.RequestAuth auth = flow.toRequestAuth(credential);
        assertEquals("account-123", auth.headers().get("chatgpt-account-id"));
        assertEquals("https://chatgpt.com/backend-api", auth.baseUrl());
    }

    @Test
    void openAiCodexDeviceFlowPollsAndExchangesDeviceAuthorization() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"device_auth_id":"device-id","user_code":"USER-CODE","interval":0}
                        """),
                json(403, "{}"),
                json(200, """
                        {"authorization_code":"authorization-code","code_verifier":"server-verifier"}
                        """),
                json(200, """
                        {"access_token":"%s","refresh_token":"refresh","expires_in":3600}
                        """.formatted(openAiJwt("account-device"))));
        TestInteraction interaction = new TestInteraction(null);
        OpenAiCodexOAuthFlow flow = new OpenAiCodexOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("device", false, null, null),
                interaction);

        assertEquals("USER-CODE", interaction.deviceUserCode);
        assertEquals("account-device", credential.getMetadata("accountId"));
        assertEquals(4, transport.requests.size());
        assertTrue(transport.requests.get(3).body().contains("server-verifier"));
    }

    @Test
    void anthropicBrowserPkceProducesClaudeOauthHeaders() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"sk-ant-oat-test","refresh_token":"refresh","expires_in":3600}
                """));
        TestInteraction interaction = new TestInteraction("anthropic-code");
        AnthropicOAuthFlow flow = new AnthropicOAuthFlow(transport);

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);
        OAuthProviderFlow.RequestAuth auth = flow.toRequestAuth(credential);

        assertEquals("Bearer sk-ant-oat-test", auth.headers().get("Authorization"));
        assertTrue(auth.headers().get("anthropic-beta").contains("oauth-2025-04-20"));
        assertTrue(transport.requests.get(0).body().contains("anthropic-code"));
    }

    @Test
    void githubCopilotDeviceFlowSupportsEnterpriseAndDerivesProxyBaseUrl() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 3600L;
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"device_code":"device","user_code":"GITHUB-CODE",
                         "verification_uri":"https://github.example.test/device",
                         "interval":1,"expires_in":600}
                        """),
                json(200, """
                        {"access_token":"github-access"}
                        """),
                json(200, """
                        {"token":"tid=x;proxy-ep=proxy.enterprise.example.test;exp=y",
                         "expires_at":%d}
                        """.formatted(expiresAt)));
        TestInteraction interaction = new TestInteraction(null);
        GitHubCopilotOAuthFlow flow = new GitHubCopilotOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions(
                        "device", false, "github.example.test", null),
                interaction);
        OAuthProviderFlow.RequestAuth auth = flow.toRequestAuth(credential);

        assertEquals("github.example.test", credential.getMetadata("enterpriseDomain"));
        assertEquals("https://api.enterprise.example.test", auth.baseUrl());
        assertEquals("vscode-chat", auth.headers().get("Copilot-Integration-Id"));
        assertEquals("GITHUB-CODE", interaction.deviceUserCode);
        assertEquals("https://api.github.example.test/copilot_internal/v2/token",
                transport.requests.get(2).uri().toString());
    }

    @Test
    void xaiDeviceFlowStoresRefreshableSubscriptionToken() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"device_code":"device","user_code":"XAI-CODE",
                         "verification_uri":"https://auth.x.ai/device",
                         "interval":1,"expires_in":600}
                        """),
                json(200, """
                        {"access_token":"xai-access","refresh_token":"xai-refresh","expires_in":3600}
                        """));
        TestInteraction interaction = new TestInteraction(null);
        XaiOAuthFlow flow = new XaiOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("device", false, null, null),
                interaction);

        assertEquals("xai-refresh", credential.getRefresh());
        assertEquals("https://api.x.ai/v1", flow.toRequestAuth(credential).baseUrl());
        assertEquals("XAI-CODE", interaction.deviceUserCode);
    }

    @Test
    void refreshPreservesExistingRefreshTokenWhenProviderDoesNotRotateIt() throws Exception {
        QueueTransport openAiTransport = new QueueTransport(json(200, """
                {"access_token":"%s","expires_in":3600}
                """.formatted(openAiJwt("refreshed-account"))));
        OpenAiCodexOAuthFlow openAi = new OpenAiCodexOAuthFlow(openAiTransport, immediatePoller());
        ManagedCredential openAiRefreshed = openAi.refresh(ManagedCredential.oauth(
                "old-openai-access",
                "old-openai-refresh",
                1L,
                Map.of("accountId", "old-account")));
        assertEquals("old-openai-refresh", openAiRefreshed.getRefresh());

        QueueTransport anthropicTransport = new QueueTransport(json(200, """
                {"access_token":"new-anthropic-access","expires_in":3600}
                """));
        AnthropicOAuthFlow anthropic = new AnthropicOAuthFlow(anthropicTransport);
        ManagedCredential anthropicRefreshed = anthropic.refresh(ManagedCredential.oauth(
                "old-anthropic-access",
                "old-anthropic-refresh",
                1L));
        assertEquals("old-anthropic-refresh", anthropicRefreshed.getRefresh());

        QueueTransport radiusTransport = new QueueTransport(json(200, """
                {"access_token":"new-radius-access","expires_in":3600}
                """));
        RadiusOAuthFlow radius = new RadiusOAuthFlow(radiusTransport, immediatePoller());
        ManagedCredential radiusRefreshed = radius.refresh(ManagedCredential.oauth(
                "old-radius-access",
                "old-radius-refresh",
                1L,
                Map.of("gateway", "https://radius.test")));
        assertEquals("old-radius-refresh", radiusRefreshed.getRefresh());
        assertEquals("https://radius.test", radiusRefreshed.getMetadata("gateway"));
    }

    @Test
    void openRouterBrowserPkceStoresPermanentOauthMintedKey() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"key":"openrouter-key"}
                """));
        TestInteraction interaction = new TestInteraction(
                "http://127.0.0.1/callback?code=openrouter-code");
        OpenRouterOAuthFlow flow = new OpenRouterOAuthFlow(transport);

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("openrouter-key", credential.getAccess());
        assertEquals("", credential.getRefresh());
        assertEquals(Long.MAX_VALUE, credential.getExpires());
        assertTrue(interaction.authorizationUri.toString().contains("callback_url="));
    }

    @Test
    void radiusBrowserFlowDiscoversEndpointAndStoresGatewayMetadata() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"authorizationEndpoint":"https://login.radius.test/authorize"}
                        """),
                json(200, """
                        {"access_token":"radius-access","refresh_token":"radius-refresh",
                         "expires_in":3600,"scope":"gateway offline_access"}
                        """));
        TestInteraction interaction = new TestInteraction("radius-code");
        RadiusOAuthFlow flow = new RadiusOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions(
                        "browser", true, null, "https://radius.test/"),
                interaction);

        assertEquals("https://radius.test", credential.getMetadata("gateway"));
        assertTrue(interaction.authorizationUri.toString().startsWith(
                "https://login.radius.test/authorize"));
        assertEquals("https://radius.test", flow.toRequestAuth(credential).baseUrl());
    }

    @Test
    void radiusDeviceFlowHandlesPendingThenCompletes() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"device_code":"radius-device","user_code":"RADIUS-CODE",
                         "verification_uri":"https://radius.test/device",
                         "interval":1,"expires_in":600}
                        """),
                json(400, """
                        {"error":"authorization_pending"}
                        """),
                json(200, """
                        {"access_token":"radius-access","refresh_token":"radius-refresh",
                         "expires_in":3600}
                        """));
        TestInteraction interaction = new TestInteraction(null);
        RadiusOAuthFlow flow = new RadiusOAuthFlow(transport, immediatePoller());

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions(
                        "device", false, null, "https://radius.test"),
                interaction);

        assertEquals("radius-access", credential.getAccess());
        assertEquals("RADIUS-CODE", interaction.deviceUserCode);
        assertEquals(3, transport.requests.size());
    }

    private static OAuthSupport.DeviceCodePoller immediatePoller() {
        return new OAuthSupport.DeviceCodePoller(millis -> {
        }, System::currentTimeMillis);
    }

    private static OAuthSupport.Response json(int status, String body) {
        return new OAuthSupport.Response(status, body);
    }

    private static String openAiJwt(String accountId) throws Exception {
        ObjectNode payload = OAuthSupport.MAPPER.createObjectNode();
        payload.putObject("https://api.openai.com/auth")
                .put("chatgpt_account_id", accountId);
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                OAuthSupport.MAPPER.writeValueAsBytes(payload));
        return header + "." + encodedPayload + ".signature";
    }

    private static final class QueueTransport implements OAuthSupport.HttpTransport {
        private final Deque<OAuthSupport.Response> responses = new ArrayDeque<>();
        private final List<OAuthSupport.Request> requests = new ArrayList<>();

        private QueueTransport(OAuthSupport.Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public OAuthSupport.Response send(OAuthSupport.Request request) {
            requests.add(request);
            if (responses.isEmpty()) {
                fail("Unexpected OAuth HTTP request: " + request.uri());
            }
            return responses.removeFirst();
        }
    }

    private static final class TestInteraction implements OAuthProviderFlow.Interaction {
        private final String promptResponse;
        private URI authorizationUri;
        private String deviceUserCode;

        private TestInteraction(String promptResponse) {
            this.promptResponse = promptResponse;
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            authorizationUri = url;
        }

        @Override
        public void deviceCode(
                String userCode,
                URI verificationUri,
                Integer intervalSeconds,
                Integer expiresInSeconds) {
            deviceUserCode = userCode;
        }

        @Override
        public String prompt(String message) {
            return promptResponse;
        }
    }
}
