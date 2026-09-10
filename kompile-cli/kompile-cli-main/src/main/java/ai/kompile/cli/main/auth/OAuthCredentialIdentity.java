package ai.kompile.cli.main.auth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local account matching hints, never authentication/authorization proof. */
public final class OAuthCredentialIdentity {
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final String OPENAI_AUTH = "https://api.openai.com/auth";
    private static final List<String> CONTEXT = List.of(
            "issuer", "organizationId", "tenant", "cloudId", "workspaceId", "clientId", "gateway", "enterpriseDomain");

    private OAuthCredentialIdentity() { }

    /** Retain only non-secret identity claims; never persist an ID token or its expiry. */
    public static Map<String, String> tokenMetadata(
            String provider, JsonNode response, ManagedCredential previous) {
        Map<String, String> metadata = new LinkedHashMap<>(
                previous == null ? Map.of() : previous.getMetadata());
        addClaims(provider, claims(text(response, "id_token")), metadata);
        addClaims(provider, claims(text(response, "access_token")), metadata);
        if ("anthropic".equalsIgnoreCase(provider)) {
            put(metadata, "accountId", text(response.path("account"), "uuid"));
            put(metadata, "email", text(response.path("account"), "email_address"));
            put(metadata, "organizationId", text(response.path("organization"), "uuid"));
        }
        put(metadata, "scope", text(response, "scope"));
        return metadata;
    }

    /** Also recovers identity/expiry from older stored JWTs without contacting a provider. */
    public static ManagedCredential normalize(String provider, ManagedCredential credential) {
        if (credential == null || !credential.isOAuth()) return credential;
        Map<String, String> metadata = new LinkedHashMap<>(credential.getMetadata());
        JsonNode payload = claims(credential.getAccess());
        addClaims(provider, payload, metadata);
        long expires = credential.getExpires();
        JsonNode exp = payload.path("exp");
        if (exp.isNumber()) {
            // Fractional NumericDate values are legal. Round down conservatively;
            // a negative deadline is already expired, never an absent expiry.
            java.math.BigDecimal millis = exp.decimalValue().multiply(java.math.BigDecimal.valueOf(1000L));
            if (millis.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
                expires = Math.min(expires, millis.signum() <= 0 ? 1L : Math.max(1L, millis.longValue()));
            }
        }
        return expires == credential.getExpires() && metadata.equals(credential.getMetadata())
                ? credential : ManagedCredential.oauth(
                        credential.getAccess(), credential.getRefresh(), expires, metadata);
    }

    public static boolean sameAccount(String provider, ManagedCredential left, ManagedCredential right) {
        if (left == null || right == null || !left.isOAuth() || !right.isOAuth()) return false;
        // A tenant, gateway or OAuth client is part of the credential's identity boundary.
        for (String key : CONTEXT) {
            if (!Objects.equals(left.getMetadata(key), right.getMetadata(key))) return false;
        }
        String leftAccount = left.getMetadata("accountId");
        String rightAccount = right.getMetadata("accountId");
        String leftSubject = left.getMetadata("subject");
        String rightSubject = right.getMetadata("subject");
        if (leftAccount != null && rightAccount != null && !leftAccount.equals(rightAccount)) return false;
        if (leftSubject != null && rightSubject != null && !leftSubject.equals(rightSubject)) return false;
        // Exact token matches are useful for legacy opaque credentials, but blank refresh
        // tokens (e.g. permanent OpenRouter keys) must never identify an account.
        if (left.getAccess().equals(right.getAccess())
                || (left.hasRefreshToken() && left.getRefresh().equals(right.getRefresh()))) return true;
        // A ChatGPT workspace id alone can be shared by multiple users.
        if ("openai-codex".equalsIgnoreCase(provider) && !nonBlank(leftSubject)) return false;
        return (nonBlank(leftAccount) || nonBlank(leftSubject))
                && Objects.equals(leftAccount, rightAccount)
                && Objects.equals(leftSubject, rightSubject);
    }

    /** Non-secret stable identity snapshot for safe request replay; null means unknown. */
    public static String identityKey(String provider, ManagedCredential credential) {
        if (credential == null || !credential.isOAuth()) return null;
        String account = credential.getMetadata("accountId");
        String subject = credential.getMetadata("subject");
        if ((!nonBlank(account) && !nonBlank(subject))
                || ("openai-codex".equalsIgnoreCase(provider) && !nonBlank(subject))) return null;
        StringBuilder key = new StringBuilder();
        for (String field : java.util.stream.Stream.concat(CONTEXT.stream(),
                java.util.stream.Stream.of("accountId", "subject")).toList()) {
            String value = credential.getMetadata(field);
            key.append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

    public static String label(ManagedCredential credential) {
        for (String key : List.of("email", "subject", "accountId")) {
            String value = credential.getMetadata(key);
            if (nonBlank(value)) {
                // Provider-controlled display data must not inject terminal controls.
                String safe = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
                return safe.substring(0, Math.min(100, safe.length()));
            }
        }
        return null;
    }

    private static void addClaims(String provider, JsonNode payload, Map<String, String> metadata) {
        put(metadata, "subject", text(payload, "sub"));
        put(metadata, "issuer", text(payload, "iss"));
        put(metadata, "email", text(payload, "email"));
        if ("openai-codex".equalsIgnoreCase(provider)) {
            JsonNode auth = payload.path(OPENAI_AUTH);
            put(metadata, "accountId", text(auth, "chatgpt_account_id"));
            // Prefer the stable ChatGPT user id to a token-type-specific subject.
            put(metadata, "subject", text(auth, "chatgpt_user_id"));
            put(metadata, "email", text(payload.path("https://api.openai.com/profile"), "email"));
        }
    }

    private static JsonNode claims(String token) {
        if (token != null && token.length() <= 65536) {
            String[] parts = token.split("\\.");
            if (parts.length == 3) {
                try {
                    JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
                    if (payload != null && payload.isObject()) return payload;
                } catch (Exception ignored) {
                    // Opaque or malformed tokens have no local identity hints.
                }
            }
        }
        return MAPPER.createObjectNode();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && nonBlank(value.textValue()) ? value.textValue() : null;
    }

    private static void put(Map<String, String> metadata, String key, String value) {
        if (nonBlank(value)) metadata.put(key, value);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
