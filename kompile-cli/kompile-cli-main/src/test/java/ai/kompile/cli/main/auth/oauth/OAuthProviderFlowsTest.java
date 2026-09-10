package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
    void googleBrowserPkceStoresRefreshTokenAndClientMetadata() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"ya29.google-access","refresh_token":"1//google-refresh",
                 "expires_in":3600,"scope":"https://www.googleapis.com/auth/gmail.readonly"}
                """));
        TestInteraction interaction = new TestInteraction("google-code");
        GoogleOAuthFlow flow = new GoogleOAuthFlow(transport, "test-client");

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("1//google-refresh", credential.getRefresh());
        assertTrue(interaction.authorizationUri.toString().contains("access_type=offline"));
        assertTrue(interaction.authorizationUri.toString().contains("prompt=consent"));
        assertTrue(transport.requests.get(0).body().contains("code=google-code"));
        assertTrue(transport.requests.get(0).body().contains("code_verifier="));
        assertEquals("test-client", credential.getMetadata("clientId"));
        assertEquals("ya29.google-access", flow.toRequestAuth(credential).token());
    }

    @Test
    void googleRefreshOmitsRedirectAndKeepsClientMetadata() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"ya29.new-access","refresh_token":"1//rotated",
                 "expires_in":3600}
                """));
        GoogleOAuthFlow flow = new GoogleOAuthFlow(transport, "test-client");

        ManagedCredential refreshed = flow.refresh(ManagedCredential.oauth(
                "ya29.old", "1//old-refresh", 1L));

        assertEquals("1//rotated", refreshed.getRefresh());
        String body = transport.requests.get(0).body();
        assertTrue(body.contains("grant_type=refresh_token"));
        assertFalse(body.contains("redirect_uri="));
        assertEquals("test-client", refreshed.getMetadata("clientId"));
    }

    @Test
    void googleLoginRequiresConfiguredClientId() {
        GoogleOAuthFlow flow = new GoogleOAuthFlow(request -> {
            throw new AssertionError("no HTTP before client id exists");
        }, null);

        IOException error = assertThrows(IOException.class,
                () -> flow.login(MANUAL_BROWSER, new TestInteraction("code")));
        assertTrue(error.getMessage().contains("KOMPILE_GOOGLE_CLIENT_ID"));
    }

    @Test
    void microsoftDeviceFlowStoresTenantAndClientMetadata() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"device_code":"ms-device","user_code":"MS-CODE",
                         "verification_uri":"https://microsoft.com/devicelogin",
                         "interval":1,"expires_in":600}
                        """),
                json(200, """
                        {"access_token":"ms-access","refresh_token":"ms-refresh",
                         "expires_in":3600}
                        """));
        TestInteraction interaction = new TestInteraction(null);
        MicrosoftOAuthFlow flow = new MicrosoftOAuthFlow(transport, immediatePoller(), "ms-client");

        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("device", false, null, null),
                interaction);

        assertEquals("MS-CODE", interaction.deviceUserCode);
        assertEquals("ms-refresh", credential.getRefresh());
        assertEquals("ms-client", credential.getMetadata("clientId"));
        assertEquals("common", credential.getMetadata("tenant"));
        assertTrue(transport.requests.get(0).uri().toString().contains("/common/"));
        assertEquals("ms-access", flow.toRequestAuth(credential).token());
    }

    @Test
    void microsoftBrowserUsesEnterpriseTenantOption() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"ms-access","refresh_token":"ms-refresh","expires_in":3600}
                """));
        TestInteraction interaction = new TestInteraction("ms-code");
        MicrosoftOAuthFlow flow = new MicrosoftOAuthFlow(transport, immediatePoller(), "ms-client");

        flow.login(new OAuthProviderFlow.LoginOptions(
                "browser", true, "contoso.onmicrosoft.com", null), interaction);

        assertTrue(interaction.authorizationUri.toString()
                .startsWith("https://login.microsoftonline.com/contoso.onmicrosoft.com/"));
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

    @Test
    void notionBrowserFlowExchangesCodeForNonExpiringIntegrationToken() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"ntn_notion-token","token_type":"bearer",
                 "workspace_id":"ws-123","workspace_name":"Acme"}
                """));
        TestInteraction interaction = new TestInteraction("notion-code");
        NotionOAuthFlow flow = new NotionOAuthFlow(transport, "notion-client", "notion-secret");

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("ntn_notion-token", credential.getAccess());
        assertEquals("", credential.getRefresh());
        assertEquals(Long.MAX_VALUE, credential.getExpires());
        assertEquals("ws-123", credential.getMetadata("workspaceId"));
        String request = transport.requests.get(0).body();
        assertTrue(request.contains("notion-code"));
        assertEquals("Basic bm90aW9uLWNsaWVudDpub3Rpb24tc2VjcmV0",
                transport.requests.get(0).headers().get("Authorization"));
        assertEquals("https://api.notion.com/v1", flow.toRequestAuth(credential).baseUrl());
    }

    @Test
    void notionLoginRequiresClientCredentials() {
        NotionOAuthFlow flow = new NotionOAuthFlow(request -> {
            throw new AssertionError("no HTTP before credentials exist");
        }, null, null);

        IOException error = assertThrows(IOException.class,
                () -> flow.login(MANUAL_BROWSER, new TestInteraction("code")));
        assertTrue(error.getMessage().contains("KOMPILE_NOTION_CLIENT_ID"));
    }

    @Test
    void redditBrowserFlowStoresRefreshableTokenWithBasicAuth() throws Exception {
        QueueTransport transport = new QueueTransport(json(200, """
                {"access_token":"reddit-access","refresh_token":"reddit-refresh",
                 "expires_in":3600,"scope":"identity read"}
                """));
        TestInteraction interaction = new TestInteraction("reddit-code");
        RedditOAuthFlow flow = new RedditOAuthFlow(transport, "reddit-client", "reddit-secret");

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("reddit-refresh", credential.getRefresh());
        assertTrue(interaction.authorizationUri.toString().contains("duration=permanent"));
        assertEquals("Basic cmVkZGl0LWNsaWVudDpyZWRkaXQtc2VjcmV0",
                transport.requests.get(0).headers().get("Authorization"));

        QueueTransport refreshTransport = new QueueTransport(json(200, """
                {"access_token":"reddit-new","refresh_token":"reddit-refresh",
                 "expires_in":3600}
                """));
        ManagedCredential refreshed = new RedditOAuthFlow(
                refreshTransport, "reddit-client", "reddit-secret").refresh(credential);
        assertTrue(refreshTransport.requests.get(0).body().contains("grant_type=refresh_token"));
        assertEquals("reddit-new", refreshed.getAccess());
    }

    @Test
    void atlassianBrowserFlowResolvesCloudIdAndStoresClientMetadata() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"access_token":"atlas-access","refresh_token":"atlas-refresh",
                         "expires_in":3600,"scope":"read:jira-work offline_access"}
                        """),
                json(200, """
                        [{"id":"cloud-1","name":"acme.atlassian.net","scopes":[]}]
                        """));
        TestInteraction interaction = new TestInteraction("atlassian-code");
        AtlassianOAuthFlow flow = new AtlassianOAuthFlow(transport, "atlas-client", "atlas-secret");

        ManagedCredential credential = flow.login(MANUAL_BROWSER, interaction);

        assertEquals("atlas-refresh", credential.getRefresh());
        assertEquals("atlas-client", credential.getMetadata("clientId"));
        assertEquals("cloud-1", credential.getMetadata("cloudId"));
        assertEquals("acme.atlassian.net", credential.getMetadata("cloudName"));
        assertTrue(transport.requests.get(1).headers().get("Authorization")
                .endsWith("atlas-access"));
        assertEquals("https://api.atlassian.com", flow.toRequestAuth(credential).baseUrl());
    }

    @Test
    void atlassianLoginRequiresClientCredentials() {
        AtlassianOAuthFlow flow = new AtlassianOAuthFlow(request -> {
            throw new AssertionError("no HTTP before credentials exist");
        }, null, null);

        IOException error = assertThrows(IOException.class,
                () -> flow.login(MANUAL_BROWSER, new TestInteraction("code")));
        assertTrue(error.getMessage().contains("KOMPILE_ATLASSIAN_CLIENT_ID"));
    }

    @Test
    void claudeIdentitySurvivesOpaqueTokenRotationAndDeduplicatesRelogin() throws Exception {
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"access_token":"claude-old","refresh_token":"r1","expires_in":3600,
                         "account":{"uuid":"user-123","email_address":"user@example.test"},
                         "organization":{"uuid":"org-123"}}
                        """),
                json(200, """
                        {"access_token":"claude-new","refresh_token":"r2","expires_in":3600}
                        """));
        AnthropicOAuthFlow flow = new AnthropicOAuthFlow(transport);
        ManagedCredential login = flow.login(MANUAL_BROWSER, new TestInteraction("code"));
        ManagedCredential refreshed = flow.refresh(login);
        assertEquals("user-123", refreshed.getMetadata("accountId"));
        assertEquals("org-123", refreshed.getMetadata("organizationId"));
        assertEquals("user@example.test", refreshed.getMetadata("email"));
        assertEquals("r2", refreshed.getRefresh());
        assertTrue(ai.kompile.cli.main.auth.OAuthCredentialIdentity.sameAccount("anthropic", login, refreshed));
    }

    @Test
    void codexAcceptsIdTokenIdentityWithOpaqueAccessAndPreservesItOnRefresh() throws Exception {
        String idPayload = """
                {"sub":"user-1","iss":"https://auth.openai.com","email":"user@example.test","exp":1,
                 "https://api.openai.com/auth":{"chatgpt_account_id":"account-123"}}
                """;
        String idToken = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                idPayload.getBytes(StandardCharsets.UTF_8)) + ".signature";
        QueueTransport transport = new QueueTransport(
                json(200, """
                        {"access_token":"opaque-old","id_token":"%s","refresh_token":"r1","expires_in":3600}
                        """.formatted(idToken)),
                json(200, """
                        {"access_token":"opaque-new","expires_in":3600}
                        """));
        OpenAiCodexOAuthFlow flow = new OpenAiCodexOAuthFlow(transport, immediatePoller());
        ManagedCredential login = flow.login(MANUAL_BROWSER, new TestInteraction("code"));
        ManagedCredential refreshed = flow.refresh(login);
        assertEquals("user-1", refreshed.getMetadata("subject"));
        assertEquals("account-123", flow.toRequestAuth(refreshed).headers().get("chatgpt-account-id"));
        assertEquals("r1", refreshed.getRefresh());
        assertTrue(refreshed.getExpires() > System.currentTimeMillis(), "ID token expiry is not access expiry");
        assertFalse(refreshed.getMetadata().containsValue(idToken));
    }

    @Test
    void copilotRejectsExpiredOrOverflowingAbsoluteExpiry() {
        for (long expiry : new long[]{1L, Long.MAX_VALUE}) {
            GitHubCopilotOAuthFlow flow = new GitHubCopilotOAuthFlow(new QueueTransport(json(200,
                    "{\"token\":\"session\",\"expires_at\":" + expiry + "}")), immediatePoller());
            assertThrows(IOException.class, () -> flow.refresh(
                    ManagedCredential.oauth("old", "github-token", 1L)));
        }
    }

    @Test
    void legacyCodexRejectsTokenAtExpiryRatherThanOneMinuteAfter() throws Exception {
        String token = openAiJwt("account", System.currentTimeMillis() / 1000L);
        assertThrows(IOException.class, () -> OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(token));
    }

    private static OAuthSupport.DeviceCodePoller immediatePoller() {
        return new OAuthSupport.DeviceCodePoller(millis -> {
        }, System::currentTimeMillis);
    }

    private static OAuthSupport.Response json(int status, String body) {
        return new OAuthSupport.Response(status, body);
    }

    @Test
    void legacyCodexAccessTokenRejectedWhenExpiredWithActionableMessage() throws Exception {
        long expired = System.currentTimeMillis() / 1000L - 3600L;
        String token = openAiJwt("account-123", expired);

        IOException error = assertThrows(IOException.class,
                () -> OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(token));

        assertTrue(error.getMessage().contains("expired"));
        assertTrue(error.getMessage().contains("kompile auth login openai-codex"));
        // Shape detection is unchanged: the credential picker keeps labeling legacy tokens.
        assertTrue(OpenAiCodexOAuthFlow.isLegacyAccessToken(token));
    }

    @Test
    void legacyCodexAccessTokenAcceptedWhenNotExpired() throws Exception {
        long live = System.currentTimeMillis() / 1000L + 3600L;

        OAuthProviderFlow.RequestAuth auth =
                OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(openAiJwt("account-123", live));

        assertEquals("account-123", auth.headers().get("chatgpt-account-id"));
        assertTrue(auth.oauth());
    }

    private static String openAiJwt(String accountId) throws Exception {
        return openAiJwt(accountId, null);
    }

    private static String openAiJwt(String accountId, Long expiresAtSeconds) throws Exception {
        ObjectNode payload = OAuthSupport.MAPPER.createObjectNode();
        payload.putObject("https://api.openai.com/auth")
                .put("chatgpt_account_id", accountId);
        if (expiresAtSeconds != null) {
            payload.put("exp", expiresAtSeconds);
        }
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
