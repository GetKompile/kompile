package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VendorUsageServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
    private static final String SECRET = "never-print-this-credential";
    @TempDir Path home;

    private ChatConfig config(String provider, boolean oauth) {
        ChatConfig config = mock(ChatConfig.class);
        when(config.getProvider()).thenReturn(provider);
        when(config.resolveRequestAuth()).thenReturn(new RequestAuth(SECRET, null,
                Map.of("chatgpt-account-id", "selected-account", "unrelated-header", "do-not-forward"), oauth));
        return config;
    }

    private VendorUsageService service(String body) {
        return new VendorUsageService((uri, headers) -> {
            assertEquals("https", uri.getScheme());
            assertFalse(headers.containsKey("unrelated-header"));
            return new VendorUsageService.Response(200, body);
        }, agent -> RequestAuth.oauth(SECRET, null, Map.of()), CLOCK);
    }

    @Test void codexReportsRealWindowHorizonsAndCreditBalanceWithSelectedAccount() {
        var service = new VendorUsageService((uri, headers) -> {
            assertEquals("https://chatgpt.com/backend-api/wham/usage", uri.toString());
            assertEquals("Bearer " + SECRET, headers.get("Authorization"));
            assertEquals("selected-account", headers.get("ChatGPT-Account-Id"));
            assertFalse(headers.containsKey("unrelated-header"));
            return new VendorUsageService.Response(200, """
                    {"plan_type":"plus", "rate_limit":{
                      "primary_window":{"used_percent":40,"limit_window_seconds":18000,"reset_after_seconds":3600},
                      "secondary_window":{"used_percent":80,"limit_window_seconds":604800,"reset_at":1792886400}},
                     "credits":{"balance":"12.25","unlimited":false}}
                    """);
        }, agent -> fail("Managed OAuth must not read native credentials"), CLOCK);
        var report = service.fetch(config("openai-codex", true));
        assertEquals(VendorUsageService.Status.AVAILABLE, report.status());
        assertTrue(report.text().contains("5-hour window: 40% used, 60% remaining; resets 2026-10-17T01:00:00Z"), report.text());
        assertTrue(report.text().contains("7-day window: 80% used, 20% remaining"), report.text());
        assertTrue(report.text().contains("Extra usage credit balance: 12.25 credits"));
        assertFalse(report.text().contains(SECRET));
    }

    @Test void zeroIsRealButMissingMalformedAndNullWindowsAreNeverZeroOrUnlimited() {
        var report = service("""
                {"rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":18000},
                 "secondary_window":null},"credits":{"balance":null}}
                """).fetch(config("openai", true));
        assertTrue(report.text().contains("0% used, 100% remaining (reset not reported)"));
        assertFalse(report.text().contains("7-day"));
        for (String body : new String[]{"{}", "{\"rate_limit\":null}",
                "{\"rate_limit\":{\"primary_window\":{\"used_percent\":\"NaN\"}}}"}) {
            var missing = service(body).fetch(config("openai", true));
            assertEquals(VendorUsageService.Status.UNAVAILABLE, missing.status(), missing.text());
            assertFalse(missing.text().contains("100%"));
        }
    }

    @Test void claudeReadsSubscriptionViaUsageOnlyNativeAuthAndIncludesAllWindows() {
        ChatConfig config = config("anthropic", false);
        when(config.isClaudeCliNative()).thenReturn(true);
        var service = new VendorUsageService((uri, headers) -> {
            assertEquals("https://api.anthropic.com/api/oauth/usage", uri.toString());
            assertEquals("oauth-2025-04-20", headers.get("anthropic-beta"));
            return new VendorUsageService.Response(200, """
                    {"five_hour":{"utilization":25,"resets_at":"2026-10-17T05:00:00Z"},
                     "seven_day":{"utilization":90},"seven_day_sonnet":{"utilization":10},
                     "seven_day_opus":null,"extra_usage":{"is_enabled":true,"used_credits":125,"monthly_limit":1000}}
                    """);
        }, agent -> {
            assertEquals("claude", agent);
            return RequestAuth.oauth(SECRET, null, Map.of());
        }, CLOCK);
        var report = service.fetch(config);
        assertTrue(report.text().contains("5-hour window: 25% used, 75% remaining"));
        assertTrue(report.text().contains("7-day window: 90% used, 10% remaining"));
        assertTrue(report.text().contains("7-day Sonnet window: 10% used"));
        assertTrue(report.text().contains("Extra usage spent: 125 credits"));
        assertFalse(report.text().contains("USD"));
        verify(config, never()).resolveRequestAuth();
    }

    @Test void zaiPreservesFiveHourWeeklyCreditAndMonthlyMcpWindows() {
        var report = service("""
                {"success":true,"code":200,"data":{"limits":[
                  {"type":"TOKENS_LIMIT","unit":3,"number":5,"currentValue":10,"usage":100,"percentage":99,"nextResetTime":1792198800000},
                  {"type":"CREDIT_LIMIT","unit":6,"number":1,"remaining":25,"usage":100,"percentage":75},
                  {"type":"TIME_LIMIT","unit":5,"number":1,"currentValue":2,"usage":10,"percentage":20},
                  {"type":"FUTURE_LIMIT","percentage":100}]}}
                """).fetch(config("zai", false));
        assertTrue(report.text().contains("token quota (5 hours): 10% used, 90% remaining"), report.text());
        assertTrue(report.text().contains("credit quota (1 week): 75% used, 25% remaining"));
        assertTrue(report.text().contains("MCP/search quota (monthly): 2 / 10 used"));
        assertFalse(report.text().contains("100% used"));
        assertEquals(VendorUsageService.Status.UNAVAILABLE,
                service("{\"success\":true,\"code\":200,\"data\":{\"limits\":[]}}")
                        .fetch(config("zai", false)).status());
        assertEquals(VendorUsageService.Status.ERROR,
                service("{\"success\":false,\"code\":401,\"msg\":\"" + SECRET + "\"}")
                        .fetch(config("zai", false)).status());
    }

    @Test void deepseekShowsCurrencyAndBalanceRatherThanSessionSpend() {
        var report = service("""
                {"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"12.50",
                 "granted_balance":"2.00","topped_up_balance":"10.50"}]}
                """).fetch(config("deepseek", false));
        assertTrue(report.text().contains("Available balance: 12.5 USD"));
        assertTrue(report.text().contains("Granted balance: 2 USD"));
    }

    @Test void openrouterBudgetIsNotMislabeledAsAccountCredit() {
        var report = service("""
                {"data":{"limit_remaining":20,"usage":30,"usage_weekly":5,"usage_monthly":10}}
                """).fetch(config("openrouter", false));
        assertTrue(report.text().contains("remaining budget (not account balance): 20 USD"));
        assertTrue(report.text().contains("Account credit balance requires a separate management key"));
    }

    @Test void apiCreditAndUnsupportedRoutesAreHonestAndDoNotMakeGenerationOrUsageRequests() {
        var service = new VendorUsageService((uri, headers) -> fail("Unexpected HTTP request"),
                agent -> fail("Unexpected native credential read"), CLOCK);
        for (String provider : new String[]{"openai", "anthropic", "gemini", "groq", "xai", "github-copilot", "kompile-local", "custom"}) {
            var report = service.fetch(config(provider, false));
            assertEquals(VendorUsageService.Status.UNAVAILABLE, report.status(), report.text());
            assertFalse(report.text().contains("100% remaining"));
        }
        var zai = config("zai", false);
        when(zai.getBaseUrl()).thenReturn(ZaiChatProvider.CREDITS_BASE_URL + "/");
        assertTrue(service.fetch(zai).text().contains("Pay-as-you-go API credits"));
        verify(zai, never()).resolveRequestAuth();
    }

    @Test void proxyCredentialsAreNeverSentToAnOfficialVendorOrRedirect() {
        var service = new VendorUsageService((uri, headers) -> fail("Credential leak"),
                agent -> fail("Credential leak"), CLOCK);
        for (String base : new String[]{"https://proxy.example/v1", "http://api.deepseek.com", "https://api.deepseek.com:8443", "https://user@api.deepseek.com"}) {
            ChatConfig config = config("deepseek", false);
            when(config.getBaseUrl()).thenReturn(base);
            assertEquals(VendorUsageService.Status.UNAVAILABLE, service.fetch(config).status());
            verify(config, never()).resolveRequestAuth();
        }
    }

    @Test void errorsCannotEchoBodiesTokensOrNetworkExceptionsAndDoNotRetry() {
        for (int status : new int[]{302, 401, 403, 429, 500}) {
            AtomicInteger calls = new AtomicInteger();
            var service = new VendorUsageService((uri, headers) -> {
                calls.incrementAndGet();
                return new VendorUsageService.Response(status, "{\"error\":\"" + SECRET + "\"}");
            }, agent -> null, CLOCK);
            var report = service.fetch(config("deepseek", false));
            assertEquals(1, calls.get());
            assertEquals(VendorUsageService.Status.ERROR, report.status());
            assertTrue(report.text().contains("HTTP " + status));
            assertFalse(report.text().contains(SECRET));
        }
        var service = new VendorUsageService((uri, headers) -> { throw new java.io.IOException(SECRET); }, agent -> null, CLOCK);
        assertFalse(service.fetch(config("deepseek", false)).text().contains(SECRET));
        for (String body : new String[]{"null", "[]", "not json", "x".repeat(1024 * 1024 + 1)}) {
            assertEquals(VendorUsageService.Status.ERROR, service(body).fetch(config("deepseek", false)).status());
        }
    }

    @Test void realHttpTransportDoesNotFollowRedirectsOrForwardTheCredential() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger leaked = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/must-not-follow");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/must-not-follow", exchange -> {
            leaked.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var response = VendorUsageService.get(java.net.URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/redirect"), Map.of("Authorization", "Bearer " + SECRET));
            assertEquals(302, response.status());
            assertEquals(0, leaked.get());
        } finally { server.stop(0); }
    }

    @Test void realHttpTransportBoundsTheBodyWhileReadingIt() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = new byte[1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            catch (java.io.IOException clientCancelled) { /* The size guard can stop the upload early. */ }
        });
        server.createContext("/small", exchange -> {
            byte[] body = "{\"data\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals("{\"data\":{}}", VendorUsageService.get(java.net.URI.create(base + "/small"), Map.of()).body());
            assertThrows(java.io.IOException.class, () -> VendorUsageService.get(java.net.URI.create(base + "/large"), Map.of()));
        } finally { server.stop(0); }
    }

    @Test void cancellationPreservesInterrupt() {
        var service = new VendorUsageService((uri, headers) -> { throw new InterruptedException(); }, agent -> null, CLOCK);
        try {
            assertEquals(VendorUsageService.Status.ERROR, service.fetch(config("deepseek", false)).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void nativeFileReadsDoNotImportOrModifyCredentialsAndRespectHomeOverrides() throws Exception {
        Path claude = Files.createDirectories(home.resolve("claude-custom")).resolve(".credentials.json");
        String json = "{\"claudeAiOauth\":{\"accessToken\":\"" + SECRET + "\",\"expiresAt\":4102444800000}}";
        Files.writeString(claude, json);
        RequestAuth auth = VendorUsageService.readNativeAuth("claude", home,
                name -> "CLAUDE_CONFIG_DIR".equals(name) ? claude.getParent().toString() : null);
        assertEquals(SECRET, auth.token());
        assertEquals(json, Files.readString(claude));
        assertFalse(Files.exists(home.resolve(".kompile/auth.json")));
        Path codex = Files.createDirectories(home.resolve(".codex")).resolve("auth.json");
        Files.writeString(codex, "{\"tokens\":{\"access_token\":\"" + SECRET + "\",\"account_id\":\"native-account\"}}");
        auth = VendorUsageService.readNativeAuth("codex", home, name -> null);
        assertEquals("native-account", auth.headers().get("ChatGPT-Account-Id"));
        assertFalse(auth.toString().contains(SECRET));
    }

    @Test void missingFiguresAndTinyCreditBalancesAreNotRoundedIntoFakeAllowance() {
        assertEquals(VendorUsageService.Status.UNAVAILABLE,
                service("{\"plan_type\":\"plus\"}").fetch(config("openai", true)).status());
        assertEquals(VendorUsageService.Status.UNAVAILABLE,
                service("{\"data\":{}}").fetch(config("openrouter", false)).status());
        assertTrue(service("{\"balance_infos\":[{\"currency\":\"USD\",\"total_balance\":\"0.000001\"}]}")
                .fetch(config("deepseek", false)).text().contains("0.000001 USD"));
    }

    @Test void selectedPassthroughAgentWinsOverStaleDirectProviderSettings() {
        ChatConfig config = config("anthropic", false);
        when(config.isClaudeCliNative()).thenReturn(true);
        when(config.getChatMode()).thenReturn("passthrough");
        when(config.getPassthroughAgent()).thenReturn("codex");
        var service = new VendorUsageService((uri, headers) -> {
            assertEquals("chatgpt.com", uri.getHost());
            return new VendorUsageService.Response(200, "{\"credits\":{\"balance\":1}}");
        }, agent -> {
            assertEquals("codex", agent);
            return RequestAuth.oauth(SECRET, null, Map.of("ChatGPT-Account-Id", "native-account"));
        }, CLOCK);
        assertTrue(service.fetch(config).text().contains("OpenAI Codex"));
        verify(config, never()).resolveRequestAuth();
    }

    @Test void nativeMissingAndExpiredCredentialsNeverFallBackToAnotherApiAccount() throws Exception {
        assertNull(VendorUsageService.readNativeAuth("claude", home, name -> null));
        Path file = Files.createDirectories(home.resolve(".claude")).resolve(".credentials.json");
        Files.writeString(file, "{\"claudeAiOauth\":{\"accessToken\":\"" + SECRET + "\",\"expiresAt\":1}}");
        assertThrows(java.io.IOException.class, () -> VendorUsageService.readNativeAuth("claude", home, name -> null));
        ChatConfig config = config("anthropic", false);
        when(config.isClaudeCliNative()).thenReturn(true);
        var service = new VendorUsageService((uri, headers) -> fail("No HTTP without native login"), agent -> null, CLOCK);
        assertEquals(VendorUsageService.Status.UNAVAILABLE, service.fetch(config).status());
        verify(config, never()).resolveRequestAuth();
    }
}
