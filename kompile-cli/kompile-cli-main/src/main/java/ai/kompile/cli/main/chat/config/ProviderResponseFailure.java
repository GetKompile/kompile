package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Structured signals only: never infer authentication or policy from model prose. */
final class ProviderResponseFailure {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProviderResponseFailure() { }

    /** Allowlisted metadata only; never dump response headers or the complete JSON envelope. */
    static String diagnostics(String body, java.net.http.HttpHeaders headers) {
        StringBuilder detail = new StringBuilder();
        JsonNode root = null;
        try {
            root = JSON.readTree(body);
        } catch (Exception ignored) { /* Header request IDs remain useful for non-JSON errors. */ }
        if (root != null) {
            JsonNode error = root.has("error") ? root.path("error") : root;
            String code = error.path("code").asText("");
            String type = error.path("type").asText("");
            String param = error.path("param").asText("");
            appendDiagnostic(detail, "code", code);
            appendDiagnostic(detail, "type", type);
            appendDiagnostic(detail, "param", param);
            for (String field : new String[]{"resets_at", "reset_at", "resets_in_seconds", "retry_after"}) {
                appendDiagnostic(detail, field, error.path(field).asText(""));
            }
            String message = error.path("message").asText("");
            if ((!code.isBlank() || !type.isBlank() || !param.isBlank())
                    && safeDiagnosticMessage(message)) {
                appendDiagnostic(detail, "message", message);
            }
        }
        String requestId = headers.firstValue("x-request-id")
                .filter(value -> !value.isBlank())
                .orElseGet(() -> headers.firstValue("request-id").orElse(""));
        if (requestId.isBlank() && root != null) {
            requestId = root.path("request_id").asText("");
            if (requestId.isBlank()) requestId = root.path("error").path("request_id").asText("");
        }
        appendDiagnostic(detail, "request_id", requestId);
        // Only quota timing headers, never credentials or arbitrary headers.
        headers.firstValue("retry-after").ifPresent(value -> {
            if (value.matches("[0-9]{1,9}")) appendDiagnostic(detail, "retry_after", value);
            else {
                try {
                    appendDiagnostic(detail, "reset_at", Long.toString(java.time.ZonedDateTime.parse(
                            value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()));
                } catch (java.time.DateTimeException ignored) { /* Invalid timing is not evidence. */ }
            }
        });
        headers.firstValue("anthropic-ratelimit-unified-reset")
                .ifPresent(value -> appendDiagnostic(detail, "reset_at", value));
        for (String window : new String[]{"5h", "7d"}) {
            if (headers.firstValue("anthropic-ratelimit-unified-" + window + "-status")
                    .filter("rejected"::equalsIgnoreCase).isPresent()) {
                headers.firstValue("anthropic-ratelimit-unified-" + window + "-reset")
                        .ifPresent(value -> appendDiagnostic(detail, "reset_at", value));
            }
        }
        return detail.isEmpty() ? "" : " (" + detail + ")";
    }

    /**
     * Allowlisted detail from an in-band error envelope's upstream metadata
     * (OpenRouter's {@code metadata.provider_name} / {@code metadata.raw}), so a
     * bare "Provider returned error" wrap names the routed provider and its real
     * failure. Bounded and sanitized via the same rules as HTTP diagnostics;
     * never dumps the full envelope.
     */
    static String upstreamDetail(JsonNode error) {
        if (error == null || error.isMissingNode() || error.isNull()) return "";
        StringBuilder detail = new StringBuilder();
        appendDiagnostic(detail, "upstream",
                error.path("metadata").path("provider_name").asText(""));
        appendDiagnostic(detail, "code", error.path("code").asText(""));
        String raw = error.path("metadata").path("raw").asText("");
        if (safeDiagnosticMessage(raw)) {
            appendDiagnostic(detail, "raw", raw);
        }
        return detail.isEmpty() ? "" : " (" + detail + ")";
    }

    private static boolean safeDiagnosticMessage(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("secret") && !lower.contains("private source")
                && !lower.contains("api_key") && !lower.contains("apikey")
                && !lower.contains("authorization") && !lower.contains("bearer ")
                && !lower.contains("password") && !lower.contains("credential")
                && !lower.contains("cookie") && !lower.contains("token");
    }

    private static void appendDiagnostic(StringBuilder detail, String name, String value) {
        if (value == null || value.isBlank()) return;
        // Bound metadata and exclude terminal controls, bidi controls and delimiter injection.
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length() && safe.length() < 160; i++) {
            char c = value.charAt(i);
            safe.append(c >= 32 && c <= 126 && c != '[' && c != ']' && c != '(' && c != ')'
                    ? c : '_');
        }
        if (value.length() > 160) safe.append("...");
        if (!detail.isEmpty()) detail.append(", ");
        detail.append(name).append('=').append(safe);
    }

    static FailureKind classify(int status, String body) {
        return classify(null, status, body);
    }

    static FailureKind classify(String provider, int status, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root != null) {
                JsonNode error = root.has("error") ? root.path("error") : root;
                if ("zai".equalsIgnoreCase(provider) && (status == 429 || status == 0)) {
                    // Z.AI uses numeric business codes for exhausted/expired
                    // plans. These will not recover through connectivity retries.
                    switch (error.path("code").asText("")) {
                        case "1113", "1308", "1309", "1310", "1314",
                                "1316", "1317", "1318", "1319", "1320", "1321":
                            return FailureKind.QUOTA_EXHAUSTED;
                        default: break;
                    }
                }
                for (String code : new String[]{error.isTextual() ? error.asText() : "",
                        error.path("code").asText(""), error.path("type").asText("")}) {
                    switch (code) {
                        case "content_filter", "content_policy_violation", "safety_violation", "refusal":
                            return FailureKind.REFUSAL;
                        case "permission_error", "permission_denied", "insufficient_permissions",
                                "ip_not_authorized", "ip_not_allowed", "organization_membership_required",
                                "unsupported_country_region_territory":
                            return FailureKind.PERMISSION_DENIED;
                        case "usage_limit_reached", "insufficient_quota", "credit_balance_exhausted", "billing_hard_limit_reached",
                                "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
                                "organization_usage_limit_exceeded":
                            return FailureKind.QUOTA_EXHAUSTED;
                        default: break;
                    }
                }
            }
        } catch (Exception ignored) { /* No diagnostic body is required to retain the HTTP status. */ }
        return status == 403 ? FailureKind.PERMISSION_DENIED
                : FailureKind.NONE;
    }

    /** Only for a native transport's terminal error event, never assistant/tool prose. */
    static FailureKind classifyNativeError(String detail) {
        FailureKind structured = classify(0, detail);
        if (structured != FailureKind.NONE) return structured;
        String text = detail == null ? "" : detail.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("usage limit") || text.contains("usage_limit_reached")
                || text.contains("hit your limit") || text.contains("weekly limit")
                || text.contains("weekly usage") || text.contains("quota exhausted")
                || text.contains("insufficient_quota") || text.contains("credit balance")
                || text.contains("insufficient funds") || text.contains("account funds")
                || text.contains("insufficient balance")) {
            return FailureKind.QUOTA_EXHAUSTED;
        }
        if (text.contains("rate limit") || text.contains("rate_limit_error")
                || text.contains("too many requests")) return FailureKind.RATE_LIMITED;
        return FailureKind.NONE;
    }

    /** Optional 401 diagnostics: bounded in bytes AND total time, never printed or retained. */
    static String authenticationBody(InputStream input) {
        if (input == null) return "";
        var reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "provider-auth-diagnostic");
            thread.setDaemon(true);
            return thread;
        });
        var pending = reader.submit(() -> input.readNBytes(8193));
        try {
            byte[] bytes = pending.get(250, TimeUnit.MILLISECONDS);
            return bytes.length > 8192 ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception unavailable) {
            return "";
        } finally {
            pending.cancel(true);
            try { input.close(); } catch (Exception ignored) { }
            reader.shutdownNow();
        }
    }

    static String message(FailureKind kind) {
        return switch (kind) {
            case REFUSAL -> "[Provider declined this request under its safety policy. Signing in again will not resolve a policy refusal.]";
            case TRUNCATED -> "[Provider response was truncated. No incomplete tool calls were executed.]";
            case PERMISSION_DENIED -> "[Provider access denied. Check account permissions, organization, region, or IP restrictions; signing in again may not help.]";
            case QUOTA_EXHAUSTED -> "[Provider credits or usage limit exhausted. Check billing and account limits before retrying.]";
            default -> "[Provider request failed.]";
        };
    }

    /**
     * OpenCode's billing split explained against the model that failed. Its two
     * gateway routes share one connected API key but two wallets: the plain
     * `opencode/<model>` provider (Console/Zen) bills the Console credit
     * balance, while an OpenCode Go subscription only pays for the
     * `opencode-go/<model>` provider. The error therefore reads like a
     * rejected credential when the key authenticated fine — what changed is
     * which plan is billed. Empty outside OpenCode's own prefixes.
     */
    static String openCodeBillingGuidance(String model) {
        String value = model == null ? "" : model.trim();
        int slash = value.indexOf('/');
        String provider = slash > 0 ? value.substring(0, slash).toLowerCase(Locale.ROOT) : "";
        if ("opencode-go".equals(provider)) {
            return "[Your API key is valid — this is usage, not the key. The OpenCode Go allowance "
                    + "for opencode-go models was exhausted and Extra Usage is off: wait for the "
                    + "5-hour/weekly/monthly window to reset, or enable Extra Usage (or add Console "
                    + "credit) at https://console.opencode.ai.]";
        }
        if ("opencode".equals(provider)) {
            return "[Your API key is valid — this is the billing route, not the key. `opencode/<model>` "
                    + "models bill the Console credit balance; an OpenCode Go subscription pays only for "
                    + "`opencode-go/<model>` models. Pick an opencode-go model via /model (e.g. "
                    + "opencode-go/glm-5.2) or fund the Console balance at https://console.opencode.ai.]";
        }
        return "";
    }
}
