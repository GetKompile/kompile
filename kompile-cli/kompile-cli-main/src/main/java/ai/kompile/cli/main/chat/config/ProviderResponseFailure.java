package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    static DirectLlmClient.FailureKind classify(int status, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root != null) {
                JsonNode error = root.has("error") ? root.path("error") : root;
                for (String code : new String[]{error.isTextual() ? error.asText() : "",
                        error.path("code").asText(""), error.path("type").asText("")}) {
                    switch (code) {
                        case "content_filter", "content_policy_violation", "safety_violation", "refusal":
                            return DirectLlmClient.FailureKind.REFUSAL;
                        case "permission_error", "permission_denied", "insufficient_permissions",
                                "ip_not_authorized", "ip_not_allowed", "organization_membership_required",
                                "unsupported_country_region_territory":
                            return DirectLlmClient.FailureKind.PERMISSION_DENIED;
                        case "insufficient_quota", "credit_balance_exhausted", "billing_hard_limit_reached",
                                "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
                                "organization_usage_limit_exceeded":
                            return DirectLlmClient.FailureKind.QUOTA_EXHAUSTED;
                        default: break;
                    }
                }
            }
        } catch (Exception ignored) { /* No diagnostic body is required to retain the HTTP status. */ }
        return status == 403 ? DirectLlmClient.FailureKind.PERMISSION_DENIED
                : DirectLlmClient.FailureKind.NONE;
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

    static String message(DirectLlmClient.FailureKind kind) {
        return switch (kind) {
            case REFUSAL -> "[Provider declined this request under its safety policy. Signing in again will not resolve a policy refusal.]";
            case TRUNCATED -> "[Provider response was truncated. No incomplete tool calls were executed.]";
            case PERMISSION_DENIED -> "[Provider access denied. Check account permissions, organization, region, or IP restrictions; signing in again may not help.]";
            case QUOTA_EXHAUSTED -> "[Provider credits or usage limit exhausted. Check billing and account limits before retrying.]";
            default -> "[Provider request failed.]";
        };
    }
}
