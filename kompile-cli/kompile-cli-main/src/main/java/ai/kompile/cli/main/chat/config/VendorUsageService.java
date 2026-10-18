/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only account usage, not this conversation's token/cost estimates. Only
 * vendor-owned, fixed HTTPS endpoints are queried; proxy credentials are never
 * sent to a different host. No generation requests, retries or credential writes.
 * Native CLI credentials are read only for usage, never imported or refreshed.
 */
public final class VendorUsageService {
    public enum Status { AVAILABLE, UNAVAILABLE, ERROR }
    public record Report(Status status, String text) { }
    record Response(int status, String body) { }
    @FunctionalInterface
    interface Transport {
        Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException;
    }
    @FunctionalInterface
    interface NativeAuth {
        RequestAuth read(String agent) throws IOException;
    }

    private static final int MAX_BYTES = 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Transport transport;
    private final NativeAuth nativeAuth;
    private final Clock clock;

    public VendorUsageService() {
        this(VendorUsageService::get, agent -> readNativeAuth(agent,
                Path.of(System.getProperty("user.home")), System::getenv), Clock.systemUTC());
    }

    VendorUsageService(Transport transport, NativeAuth nativeAuth, Clock clock) {
        this.transport = transport;
        this.nativeAuth = nativeAuth;
        this.clock = clock;
    }

    public Report fetch(ChatConfig config) {
        if (config == null) return unavailable("No chat provider is configured. Select a provider first.");
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        boolean passthrough = "passthrough".equals(config.getChatMode());
        String agent = passthrough ? config.getPassthroughAgent() : provider;
        if (agent == null) agent = "";
        agent = agent.toLowerCase(Locale.ROOT);
        boolean claudeNative = passthrough ? "claude".equals(agent)
                : config.isClaudeCliNative() || "claude".equals(agent);
        boolean codexNative = "codex".equals(agent);
        String vendor = claudeNative ? "Claude" : codexNative ? "OpenAI Codex" : ChatProviderRegistry.label(provider);
        String heading = "Vendor usage — " + vendor + "\n";
        try {
            if (!passthrough && !canonicalHost(config)) {
                return unavailable(heading + "Account usage is not queried for a custom/proxy endpoint. Check that endpoint's billing dashboard.");
            }
            if (claudeNative || codexNative) {
                RequestAuth auth = nativeAuth.read(claudeNative ? "claude" : "codex");
                if (auth == null) return unavailable(heading + "Native CLI usage credentials are unavailable here. Check "
                        + (claudeNative ? "/usage in Claude Code" : "/status in Codex") + "; OS-keychain-only logins are not read by Kompile.");
                return render(heading, claudeNative ? claude(auth) : codex(auth));
            }
            if (passthrough) return unavailable(heading + "This native agent does not expose an account-usage adapter. Check its vendor dashboard.");
            // Do not resolve (or refresh) credentials for providers without a usage endpoint.
            String guidance = switch (provider) {
                case "anthropic" -> "Anthropic does not expose remaining API credit through an inference API key. Check https://platform.claude.com/settings/billing . Subscription limits require the Claude Code login route.";
                case "gemini" -> "Gemini API quota/billing is managed in Google AI Studio or Cloud Console: https://aistudio.google.com/usage . It is not a Gemini subscription allowance.";
                case "groq" -> "Account usage is available at https://console.groq.com/settings/usage ; no balance endpoint is exposed through this chat adapter.";
                case "xai" -> "Account credits/usage are available at https://console.x.ai/ ; no balance endpoint is exposed through this chat adapter.";
                case "github-copilot" -> "Check https://github.com/settings/copilot for account quotas. The inference session token does not expose billing usage.";
                case "kompile", "kompile-local" -> "Local/Kompile serving has no remote vendor allowance. Use /stats for this session's token usage.";
                default -> null;
            };
            if (guidance != null) return unavailable(heading + guidance);
            if (!List.of("openai", "openai-codex", "zai", "deepseek", "openrouter").contains(provider)) {
                return unavailable(heading + "This provider does not expose an account-usage adapter. Check its billing dashboard; /stats is session usage only.");
            }
            if ("zai".equals(provider) && config.getBaseUrl() != null
                    && config.getBaseUrl().replaceAll("/+$", "").equals(ZaiChatProvider.CREDITS_BASE_URL)) {
                return unavailable(heading + "Pay-as-you-go API credits: no supported balance endpoint. Check https://z.ai/manage-apikey/billing . Coding-plan quotas are separate from API credit.");
            }
            RequestAuth auth = config.resolveRequestAuth();
            if (auth == null) return unavailable(heading + "No credential is selected for this provider. Use /auth.");
            return switch (provider) {
                case "openai", "openai-codex" -> auth.oauth() ? render(heading, codex(auth))
                        : unavailable(heading + "OpenAI does not expose remaining API credit through an inference API key. Check https://platform.openai.com/usage and https://platform.openai.com/settings/organization/billing/overview . ChatGPT/Codex 5-hour and weekly allowances require the OpenAI subscription login route.");
                case "zai" -> render(heading, zai(auth));
                case "deepseek" -> render(heading, deepseek(auth));
                case "openrouter" -> render(heading, openrouter(auth));
                default -> throw new IllegalStateException();
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Report(Status.ERROR, heading + "Usage lookup cancelled.");
        } catch (UsageFailure failure) {
            return new Report(Status.ERROR, heading + failure.getMessage());
        } catch (ChatConfig.AuthenticationException failure) {
            return new Report(Status.ERROR, heading + "Cannot resolve the selected account. Use /auth to sign in again; no other account was queried.");
        } catch (Exception failure) {
            // Neither HTTP bodies nor exception messages are safe to print: they can echo secrets.
            return new Report(Status.ERROR, heading + "Usage lookup failed (network, credentials, or invalid vendor response). No allowance was inferred; check the vendor dashboard.");
        }
    }

    private static boolean canonicalHost(ChatConfig config) {
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) return true;
        String expected = ChatProviderRegistry.defaultBaseUrl(config.getProvider());
        if (expected == null) return false;
        URI uri = URI.create(base);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && URI.create(expected).getHost().equalsIgnoreCase(uri.getHost());
    }

    private List<String> codex(RequestAuth auth) throws IOException, InterruptedException {
        Map<String, String> headers = bearer(auth);
        auth.headers().forEach((key, value) -> {
            if ("chatgpt-account-id".equalsIgnoreCase(key)) headers.put("ChatGPT-Account-Id", value);
        });
        JsonNode body = query("https://chatgpt.com/backend-api/wham/usage", headers);
        List<String> lines = new ArrayList<>();
        String plan = body.path("plan_type").asText("");
        codexWindows(lines, body.path("rate_limit"), "");
        codexWindows(lines, body.path("code_review_rate_limit"), "Code review ");
        JsonNode credits = body.path("credits");
        if (credits.path("unlimited").asBoolean(false)) lines.add("Extra usage credits: unlimited");
        else addNumber(lines, "Extra usage credit balance", credits.get("balance"), " credits");
        if (!lines.isEmpty() && plan.matches("[A-Za-z0-9_-]{1,40}")) lines.add(0, "Subscription plan: " + plan);
        return lines;
    }

    private void codexWindows(List<String> lines, JsonNode limits, String prefix) {
        for (String field : List.of("primary_window", "secondary_window")) {
            JsonNode window = limits.path(field);
            Long seconds = integer(window.get("limit_window_seconds"));
            String label = seconds == null || seconds <= 0 ? field.replace('_', ' ')
                    : seconds % 86400 == 0 ? seconds / 86400 + "-day window"
                    : seconds % 3600 == 0 ? seconds / 3600 + "-hour window" : seconds + "-second window";
            Instant reset = epoch(window.get("reset_at"), false);
            Long after = integer(window.get("reset_after_seconds"));
            if (reset == null && after != null && after >= 0 && after <= 366L * 86400) reset = clock.instant().plusSeconds(after);
            addWindow(lines, prefix + label, window.get("used_percent"), reset);
        }
        if (limits.path("limit_reached").asBoolean(false)) lines.add(prefix + "Allowance exhausted.");
    }

    private List<String> claude(RequestAuth auth) throws IOException, InterruptedException {
        Map<String, String> headers = bearer(auth);
        headers.put("anthropic-beta", "oauth-2025-04-20");
        JsonNode body = query("https://api.anthropic.com/api/oauth/usage", headers);
        List<String> lines = new ArrayList<>();
        Map<String, String> windows = new LinkedHashMap<>();
        windows.put("five_hour", "5-hour window");
        windows.put("seven_day", "7-day window");
        windows.put("seven_day_opus", "7-day Opus window");
        windows.put("seven_day_sonnet", "7-day Sonnet window");
        windows.put("seven_day_oauth_apps", "7-day OAuth apps window");
        windows.forEach((field, label) -> {
            JsonNode window = body.path(field);
            addWindow(lines, label, window.get("utilization"), iso(window.path("resets_at").asText(null)));
        });
        JsonNode extra = body.path("extra_usage");
        if (extra.path("is_enabled").asBoolean(false)) {
            // These fields are vendor credits, not dollars; don't guess a currency conversion.
            addNumber(lines, "Extra usage spent", extra.get("used_credits"), " credits");
            addNumber(lines, "Extra usage monthly limit", extra.get("monthly_limit"), " credits");
            addWindow(lines, "Extra usage monthly", extra.get("utilization"), null);
        } else if (extra.path("is_enabled").isBoolean()) lines.add("Extra usage: disabled");
        return lines;
    }

    private List<String> zai(RequestAuth auth) throws IOException, InterruptedException {
        JsonNode body = query("https://api.z.ai/api/monitor/usage/quota/limit", Map.of("Authorization", auth.token()));
        if (body.path("success").isBoolean() && !body.path("success").asBoolean()
                || body.has("code") && body.path("code").asInt() != 200) {
            throw new UsageFailure("Z.AI rejected the usage query. Check the selected key/plan and vendor dashboard.");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode limit : body.path("data").path("limits")) {
            String type = limit.path("type").asText();
            String label = switch (type) {
                case "TOKENS_LIMIT" -> "Coding plan token quota";
                case "CREDIT_LIMIT" -> "Coding plan credit quota";
                case "TIME_LIMIT" -> "MCP/search quota";
                default -> null;
            };
            if (label == null) continue;
            // Vendor units: 1=day, 3=hour, 5=minute, 6=week. The legacy
            // TIME_LIMIT 5/1 marker means monthly MCP, not a one-minute plan.
            int unit = limit.path("unit").asInt(-1);
            Long count = integer(limit.get("number"));
            if (unit == 5 && Long.valueOf(1).equals(count) && "TIME_LIMIT".equals(type)) label += " (monthly)";
            else if (count != null && count > 0) {
                String period = switch (unit) {
                    case 1 -> "day";
                    case 3 -> "hour";
                    case 5 -> "minute";
                    case 6 -> "week";
                    default -> null;
                };
                if (period != null) label += " (" + count + " " + period + (count == 1 ? "" : "s") + ")";
            }
            Double used = decimal(limit.get("currentValue"));
            Double total = decimal(limit.get("usage"));
            Double remaining = decimal(limit.get("remaining"));
            if (total != null && remaining != null && total >= 0 && remaining >= 0 && remaining <= total) {
                used = used == null ? total - remaining : Math.max(used, total - remaining);
            }
            Double percentage = decimal(limit.get("percentage"));
            if (used != null && total != null && used >= 0 && total >= 0) {
                lines.add(label + ": " + number(used) + " / " + number(total) + " used");
                if (total > 0) percentage = Math.min(100, 100 * used / total);
            }
            JsonNode percent = percentage == null ? null : JsonUtils.standardMapper().valueToTree(percentage);
            addWindow(lines, label, percent, epoch(limit.get("nextResetTime"), true));
        }
        return lines;
    }

    private List<String> deepseek(RequestAuth auth) throws IOException, InterruptedException {
        JsonNode body = query("https://api.deepseek.com/user/balance", bearer(auth));
        List<String> lines = new ArrayList<>();
        for (JsonNode balance : body.path("balance_infos")) {
            String currency = balance.path("currency").asText();
            if (!List.of("USD", "CNY").contains(currency)) continue;
            addNumber(lines, "Available balance", balance.get("total_balance"), " " + currency);
            addNumber(lines, "Granted balance", balance.get("granted_balance"), " " + currency);
            addNumber(lines, "Topped-up balance", balance.get("topped_up_balance"), " " + currency);
        }
        if (body.path("is_available").isBoolean()) lines.add("API balance sufficient: " + body.path("is_available").asBoolean());
        return lines;
    }

    private List<String> openrouter(RequestAuth auth) throws IOException, InterruptedException {
        JsonNode body = query("https://openrouter.ai/api/v1/key", bearer(auth)).path("data");
        List<String> lines = new ArrayList<>();
        addNumber(lines, "This key: remaining budget (not account balance)", body.get("limit_remaining"), " USD");
        addNumber(lines, "This key: spend", body.get("usage"), " USD");
        addNumber(lines, "This key: daily spend", body.get("usage_daily"), " USD");
        addNumber(lines, "This key: weekly spend", body.get("usage_weekly"), " USD");
        addNumber(lines, "This key: monthly spend", body.get("usage_monthly"), " USD");
        if (body.has("limit") && body.path("limit").isNull()) lines.add("This key has no configured spending cap (not unlimited account credit).");
        if (!lines.isEmpty()) lines.add("Account credit balance requires a separate management key; see https://openrouter.ai/settings/credits .");
        return lines;
    }

    private JsonNode query(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Response response = transport.get(URI.create(url), headers);
        if (response.status() < 200 || response.status() >= 300) {
            String note = switch (response.status()) {
                case 401 -> " Sign in again with this provider; the usage credential was rejected.";
                case 403 -> " This account/key lacks permission to read vendor usage.";
                case 429 -> " Vendor usage lookup is rate-limited; try again later.";
                default -> " Check the vendor dashboard; no allowance was inferred.";
            };
            throw new UsageFailure("Usage query failed (HTTP " + response.status() + ")." + note);
        }
        if (response.body() == null || response.body().length() > MAX_BYTES) throw new IOException("Invalid usage response");
        JsonNode body = JsonUtils.standardMapper().readTree(response.body());
        if (body == null || !body.isObject() || body.hasNonNull("error")) throw new IOException("Invalid usage response");
        return body;
    }

    private Report render(String heading, List<String> lines) {
        if (lines.isEmpty()) return unavailable(heading + "The vendor returned no recognized usage figures. Check its dashboard; missing values are not zero or unlimited.");
        return new Report(Status.AVAILABLE, heading + String.join("\n", lines) + "\nFetched: " + clock.instant() + " (vendor-reported; not session token estimates)");
    }

    private static Report unavailable(String text) { return new Report(Status.UNAVAILABLE, text); }
    private static Map<String, String> bearer(RequestAuth auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + auth.token());
        return headers;
    }
    private static void addNumber(List<String> lines, String label, JsonNode node, String suffix) {
        Double value = decimal(node);
        if (value != null && value >= 0) lines.add(label + ": " + number(value) + suffix);
    }
    private static void addWindow(List<String> lines, String label, JsonNode node, Instant reset) {
        Double used = decimal(node);
        if (used == null || used < 0 || used > 100) return;
        lines.add(label + ": " + percent(used) + "% used, " + percent(100 - used) + "% remaining"
                + (reset == null ? " (reset not reported)" : "; resets " + reset));
    }
    private static Double decimal(JsonNode node) {
        if (node == null || (!node.isNumber() && !node.isTextual())) return null;
        try {
            double value = Double.parseDouble(node.asText());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }
    private static Long integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) return null;
        return node.longValue();
    }
    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    private static String percent(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private static Instant epoch(JsonNode node, boolean millis) {
        Long value = integer(node);
        if (value == null || value <= 0) return null;
        try { return millis ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value); }
        catch (java.time.DateTimeException invalid) { return null; }
    }
    private static Instant iso(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (java.time.DateTimeException invalid) { return null; }
    }

    static Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("User-Agent", "kompile/usage").GET();
        headers.forEach(request::header);
        var pending = HTTP.sendAsync(request.build(), info -> new HttpResponse.BodySubscriber<byte[]>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private java.util.concurrent.Flow.Subscription subscription;
            private long received;
            private boolean failed;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            public void onNext(List<java.nio.ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) received += buffer.remaining();
                if (received > MAX_BYTES) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Usage response too large"));
                } else delegate.onNext(buffers);
            }
            public void onError(Throwable failure) { if (!failed) delegate.onError(failure); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        });
        try {
            // Bound the complete body, not only the connection/response headers.
            HttpResponse<byte[]> response = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            return new Response(response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.util.concurrent.TimeoutException timeout) {
            pending.cancel(true);
            throw new IOException("Usage lookup timed out");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IOException("Usage lookup failed");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        }
    }

    /** Usage-only read of the native CLI's file store. Never changes login or imports secrets. */
    static RequestAuth readNativeAuth(String agent, Path home, Function<String, String> env) throws IOException {
        String root = env.apply("claude".equals(agent) ? "CLAUDE_CONFIG_DIR" : "CODEX_HOME");
        Path directory = root == null || root.isBlank() ? home.resolve("claude".equals(agent) ? ".claude" : ".codex") : Path.of(root);
        Path path = directory.resolve("claude".equals(agent) ? ".credentials.json" : "auth.json");
        if (!Files.isRegularFile(path)) return null;
        if (Files.size(path) > MAX_BYTES) throw new IOException("Invalid native usage credential");
        JsonNode body = JsonUtils.standardMapper().readTree(Files.readString(path));
        JsonNode credentials = body.path("claude".equals(agent) ? "claudeAiOauth" : "tokens");
        String token = credentials.path("claude".equals(agent) ? "accessToken" : "access_token").asText("");
        if (token.isBlank()) return null;
        Long expires = integer(credentials.get("expiresAt"));
        if (expires != null && expires <= System.currentTimeMillis()) throw new UsageFailure("Native usage credential expired. Open the vendor CLI to renew its login.");
        Map<String, String> headers = new LinkedHashMap<>();
        if ("codex".equals(agent)) {
            String account = credentials.path("account_id").asText("");
            if (account.isBlank()) {
                try {
                    String[] jwt = token.split("\\.");
                    account = JsonUtils.standardMapper().readTree(Base64.getUrlDecoder().decode(jwt[1]))
                            .path("https://api.openai.com/auth").path("chatgpt_account_id").asText("");
                } catch (Exception invalid) { throw new IOException("Invalid native usage credential"); }
            }
            if (account.isBlank()) throw new IOException("Missing native account identity");
            headers.put("ChatGPT-Account-Id", account);
        }
        return RequestAuth.oauth(token, null, headers);
    }

    private static final class UsageFailure extends IOException {
        UsageFailure(String message) { super(message); }
    }
}
