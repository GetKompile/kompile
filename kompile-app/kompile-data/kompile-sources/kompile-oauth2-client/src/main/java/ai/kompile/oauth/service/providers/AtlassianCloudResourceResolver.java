/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.oauth.service.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Resolves Atlassian 3LO accessible-resource metadata into product API base URLs. */
public final class AtlassianCloudResourceResolver {

    private AtlassianCloudResourceResolver() {
    }

    public static String resolveCloudId(ObjectMapper mapper, String providerData, String siteBaseUrl) {
        if (providerData == null || providerData.isBlank()) return null;
        try {
            JsonNode resources = mapper.readTree(providerData);
            if (!resources.isArray()) return null;
            String normalizedBase = normalize(siteBaseUrl);
            for (JsonNode resource : resources) {
                String resourceUrl = normalize(resource.path("url").asText(""));
                if (!resourceUrl.isBlank()
                        && (normalizedBase.equalsIgnoreCase(resourceUrl)
                        || normalizedBase.regionMatches(true, 0, resourceUrl, 0, resourceUrl.length())
                                && normalizedBase.length() > resourceUrl.length()
                                && normalizedBase.charAt(resourceUrl.length()) == '/')) {
                    return resource.path("id").asText(null);
                }
            }
            return null;
        } catch (IOException invalidProviderData) {
            return null;
        }
    }

    public static String confluenceApiBase(String cloudId) {
        return productApiBase("confluence", cloudId) + "/wiki";
    }

    public static String jiraApiBase(String cloudId) {
        return productApiBase("jira", cloudId);
    }

    private static String productApiBase(String product, String cloudId) {
        if (cloudId == null || !cloudId.matches("[A-Za-z0-9-]{8,128}")) {
            throw new IllegalArgumentException("Invalid Atlassian cloudId");
        }
        return "https://api.atlassian.com/ex/" + product + "/" + cloudId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
