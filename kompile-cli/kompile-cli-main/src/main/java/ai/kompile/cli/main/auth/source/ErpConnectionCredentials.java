/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.auth.source;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.source.erp.ErpSourceConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Named service-bound ERP secret envelopes in the existing owner-private credential store.
 * The opaque API-key slot holds the entire envelope, never non-secret OAuth metadata.
 * Bearer tokens are provisioned externally; this service does not acquire or refresh them.
 */
public final class ErpConnectionCredentials {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ErpConnectionCredentials() {}
    public static boolean isErp(String type) { return ErpSourceConfiguration.isErp(type); }
    public static String provider(String type) {
        if (!isErp(type)) throw new IllegalArgumentException("Unsupported ERP source type");
        return "erp-" + type.toLowerCase(Locale.ROOT).replace('_', '-');
    }
    public static void save(CredentialStore store, String type, String name, String root, String tenant,
                            String username, String secret, long expiresAt) throws IOException {
        provider(type);
        var serviceUri = ErpSourceConfiguration.serviceRoot(root);
        ErpSourceConfiguration.validateProfileRoot(type, serviceUri);
        String service = serviceUri.toString();
        if (tenant != null && (tenant.contains("\r") || tenant.contains("\n"))) throw new IllegalArgumentException("Invalid ERP tenant");
        if (secret == null || secret.isBlank() || secret.contains("\r") || secret.contains("\n"))
            throw new IllegalArgumentException("ERP secret must be nonblank and single-line");
        if ("INFOR_MONGOOSE".equalsIgnoreCase(type)) ErpSourceConfiguration.validateMongooseConfig(tenant);
        boolean basic = username != null && !username.isBlank();
        if (basic && (username.contains(":") || username.contains("\r") || username.contains("\n")))
            throw new IllegalArgumentException("Invalid ERP username");
        if (basic && ErpSourceConfiguration.requiresBearer(type))
            throw new IllegalArgumentException("This ERP profile requires a bearer token, not basic credentials");
        if (!basic && ErpSourceConfiguration.requiresBasic(type))
            throw new IllegalArgumentException("This ERP profile requires a username and password");
        if (expiresAt < 0 || expiresAt > 0 && expiresAt <= System.currentTimeMillis())
            throw new IllegalArgumentException("ERP token expiry must be in the future");
        var data = MAPPER.createObjectNode();
        data.put("version", 1);
        data.put("serviceRoot", service);
        data.put("tenant", tenant == null ? "" : tenant.trim());
        data.put("username", basic ? username.trim() : "");
        data.put("authMode", basic ? "basic" : "bearer");
        data.put(basic ? "password" : "accessToken", secret);
        data.put("expiresAt", expiresAt);
        store.put(provider(type), name, ManagedCredential.apiKey(data.toString()), false);
    }
    public static Map<String, Object> status(CredentialStore store, String type, String name) throws IOException {
        JsonNode value = read(store, type, name);
        Map<String, Object> info = new LinkedHashMap<>();
        for (String key : new String[]{"serviceRoot", "tenant", "username", "authMode"}) info.put(key, value.path(key).asText());
        info.put("expiresAt", value.path("expiresAt").asLong());
        info.put("expired", value.path("expiresAt").asLong() > 0 && value.path("expiresAt").asLong() <= System.currentTimeMillis());
        return info;
    }
    public static void resolve(CredentialStore store, String type, Map<String, Object> properties) throws IOException {
        if (!isErp(type)) return;
        if (properties.containsKey("fromChannelConnection")) throw new IllegalArgumentException("ERP uses connectionName, not a channel connection");
        String name = ErpSourceConfiguration.text(properties, "connectionName", "");
        if (name.isBlank()) return; // Explicit one-shot local ingest credentials or public collections.
        for (String key : properties.keySet()) if (SourceCredentialResolver.isSensitiveProperty(key))
            throw new IllegalArgumentException("Named ERP connections cannot override secret properties");
        JsonNode value = read(store, type, name);
        long expiry = value.path("expiresAt").asLong();
        if (expiry > 0 && expiry <= System.currentTimeMillis())
            throw new IllegalArgumentException("ERP bearer credential expired; configure the connection again");
        Object root = properties.get("serviceRoot");
        if (root != null && !ErpSourceConfiguration.serviceRoot(root.toString()).toString().equals(value.path("serviceRoot").asText()))
            throw new IllegalArgumentException("ERP serviceRoot does not match the named connection");
        for (String key : new String[]{"tenant", "username", "authMode"}) {
            Object requested = properties.get(key);
            String stored = value.path(key).asText();
            if (requested != null && !requested.toString().trim().equals(stored))
                throw new IllegalArgumentException("ERP " + key + " does not match the named connection");
        }
        for (String key : new String[]{"serviceRoot", "tenant", "username", "authMode", "password", "accessToken"}) {
            String stored = value.path(key).asText();
            if (!stored.isEmpty()) properties.put(key, stored);
        }
    }
    private static JsonNode read(CredentialStore store, String type, String name) throws IOException {
        ManagedCredential credential = store.read(provider(type), name);
        if (credential == null || !credential.isApiKey())
            throw new IOException("Named ERP connection not found; run 'kompile auth source erp'");
        try {
            JsonNode value = MAPPER.readTree(credential.getKey());
            if (!value.isObject() || value.path("version").asInt() != 1
                    || !(value.path("authMode").asText().equals("basic") && value.hasNonNull("password")
                    || value.path("authMode").asText().equals("bearer") && value.hasNonNull("accessToken"))) throw new IllegalArgumentException();
            ErpSourceConfiguration.serviceRoot(value.path("serviceRoot").asText());
            return value;
        } catch (Exception e) { throw new IOException("Invalid stored ERP connection; configure it again"); }
    }
}
