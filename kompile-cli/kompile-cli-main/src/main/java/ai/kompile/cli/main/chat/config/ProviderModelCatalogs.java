/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data-driven catalog transport contracts for direct chat providers.
 *
 * <p>This registry contains endpoint and response-shape metadata only. Model ids
 * always come from the provider's live catalog response.</p>
 */
final class ProviderModelCatalogs {
    private static final String RESOURCE =
            "ai/kompile/cli/main/chat/model-catalogs.json";
    private static final ObjectMapper MAPPER =
            ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final CatalogState STATE = load();

    private ProviderModelCatalogs() {
    }

    static ModelDiscovery.Strategy strategy(String providerId) {
        return context -> {
            Descriptor descriptor = find(providerId);
            if (descriptor == null) {
                String detail = STATE.error().isBlank()
                        ? "Provider has no configured live model catalog"
                        : "Model catalog configuration is unavailable: " + STATE.error();
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.UNSUPPORTED, detail, List.of());
            }
            String endpoint = descriptor.endpoint(context == null ? null : context.baseUrl());
            if (endpoint == null || endpoint.isBlank()) {
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.UNSUPPORTED,
                        "Provider has no configured live model catalog endpoint",
                        List.of());
            }
            return ModelDiscoveryHttp.discover(context, List.of(endpoint), descriptor);
        };
    }

    static Descriptor find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        return STATE.descriptors().get(normalize(providerId));
    }

    static Map<String, Descriptor> all() {
        return STATE.descriptors();
    }

    private static CatalogState load() {
        try (InputStream input = ProviderModelCatalogs.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return new CatalogState(Map.of(), "missing classpath resource " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(input);
            if (root.path("schemaVersion").asInt(-1) != 1
                    || !root.path("providers").isArray()) {
                return new CatalogState(Map.of(), "invalid catalog descriptor schema");
            }
            Map<String, Descriptor> descriptors = new LinkedHashMap<>();
            for (JsonNode node : root.path("providers")) {
                Descriptor descriptor = new Descriptor(
                        node.path("id").asText(""),
                        Route.valueOf(node.path("route").asText("DIRECT")),
                        node.path("path").asText(""),
                        node.path("stripSuffix").asText(""),
                        node.path("responseProfile").asText("STANDARD"),
                        strings(node.path("idFields")),
                        stringMap(node.path("query")),
                        node.path("paginationQuery").asText(""),
                        node.path("paginationToken").asText(""),
                        node.path("paginationHasMore").asText(""));
                if (descriptor.id().isBlank() || descriptor.path().isBlank()) {
                    return new CatalogState(Map.of(), "catalog descriptor is missing id or path");
                }
                if (descriptors.put(normalize(descriptor.id()), descriptor) != null) {
                    return new CatalogState(Map.of(),
                            "duplicate catalog descriptor for " + descriptor.id());
                }
            }
            return new CatalogState(Map.copyOf(descriptors), "");
        } catch (Exception error) {
            String message = error.getMessage();
            return new CatalogState(Map.of(), message == null || message.isBlank()
                    ? error.getClass().getSimpleName() : message);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> strings(JsonNode values) {
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim());
            }
        });
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode values) {
        if (values == null || !values.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                result.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Map.copyOf(result);
    }

    enum Route {
        DIRECT,
        VERSIONED,
        STRIP_SUFFIX
    }

    record Descriptor(
            String id,
            Route route,
            String path,
            String stripSuffix,
            String responseProfile,
            List<String> idFields,
            Map<String, String> query,
            String paginationQuery,
            String paginationToken,
            String paginationHasMore) {
        Descriptor {
            id = id == null ? "" : id.trim();
            route = route == null ? Route.DIRECT : route;
            path = path == null ? "" : path.trim();
            stripSuffix = stripSuffix == null ? "" : stripSuffix.trim();
            responseProfile = responseProfile == null || responseProfile.isBlank()
                    ? "STANDARD" : responseProfile.trim().toUpperCase(Locale.ROOT);
            idFields = idFields == null ? List.of() : List.copyOf(idFields);
            query = query == null ? Map.of() : Map.copyOf(query);
            paginationQuery = paginationQuery == null ? "" : paginationQuery.trim();
            paginationToken = paginationToken == null ? "" : paginationToken.trim();
            paginationHasMore = paginationHasMore == null ? "" : paginationHasMore.trim();
        }

        String endpoint(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                return null;
            }
            String endpoint = switch (route) {
                case DIRECT -> ModelDiscoveryHttp.endpoint(baseUrl, path);
                case VERSIONED -> {
                    String base = baseUrl.replaceAll("/+$", "");
                    yield base.endsWith("/v1") || base.endsWith("/openai/v1")
                            ? ModelDiscoveryHttp.endpoint(base, path)
                            : ModelDiscoveryHttp.endpoint(base, "/v1" + path);
                }
                case STRIP_SUFFIX -> ModelDiscoveryHttp.endpointWithoutSuffix(
                        baseUrl, stripSuffix, path);
            };
            if (endpoint == null || query.isEmpty()) return endpoint;
            StringBuilder value = new StringBuilder(endpoint);
            query.forEach((name, item) -> value
                    .append(value.indexOf("?") >= 0 ? '&' : '?')
                    .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(item, StandardCharsets.UTF_8)));
            return value.toString();
        }

        boolean paginated() {
            return !paginationQuery.isBlank() && !paginationToken.isBlank();
        }
    }

    private record CatalogState(Map<String, Descriptor> descriptors, String error) {
        private CatalogState {
            descriptors = descriptors == null ? Map.of() : Map.copyOf(descriptors);
            error = error == null ? "" : error;
        }
    }
}
