/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.core.source.provider.SourceProviderDto;
import ai.kompile.core.source.provider.SourceProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for exposing source provider information to the frontend.
 * This enables dynamic UI rendering based on which source modules are included.
 */
@RestController
@RequestMapping("/api/source-providers")
public class SourceProviderController {

    private static final Logger logger = LoggerFactory.getLogger(SourceProviderController.class);

    private final SourceProviderRegistry registry;

    @Autowired
    public SourceProviderController(SourceProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Gets all available source providers.
     *
     * @param includeUnavailable if true, includes unavailable providers with status
     * @return list of source provider DTOs
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSourceProviders(
            @RequestParam(defaultValue = "false") boolean includeUnavailable) {

        List<SourceProvider> providers = includeUnavailable
                ? new ArrayList<>(registry.getAllProviders())
                : registry.getAvailableProviders();

        List<SourceProviderDto> providerDtos = providers.stream()
                .map(SourceProviderDto::fromProvider)
                .sorted(Comparator
                        .comparing(SourceProviderDto::getCategory)
                        .thenComparing(SourceProviderDto::getOrder)
                        .thenComparing(SourceProviderDto::getDisplayName))
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("providers", providerDtos);
        response.put("totalCount", registry.getProviderCount());
        response.put("availableCount", registry.getAvailableProviderCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Gets source providers organized by category.
     *
     * @return map of category -> providers
     */
    @GetMapping("/by-category")
    public ResponseEntity<Map<String, List<SourceProviderDto>>> getProvidersByCategory() {
        Map<String, List<SourceProvider>> categoryMap = registry.getProvidersByCategories();

        Map<String, List<SourceProviderDto>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SourceProvider>> entry : categoryMap.entrySet()) {
            result.put(entry.getKey(),
                    entry.getValue().stream()
                            .map(SourceProviderDto::fromProvider)
                            .collect(Collectors.toList()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Gets a specific source provider by ID.
     *
     * @param providerId the provider ID
     * @return provider details or 404
     */
    @GetMapping("/{providerId}")
    public ResponseEntity<SourceProviderDto> getProvider(@PathVariable String providerId) {
        SourceProvider provider = registry.getProvider(providerId);
        if (provider == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(SourceProviderDto.fromProvider(provider));
    }

    /**
     * Gets the category metadata (display names, icons, order).
     */
    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, Object>>> getCategories() {
        // Define standard category metadata
        List<Map<String, Object>> categories = Arrays.asList(
                createCategoryMeta("local", "Local Sources", "computer", 1,
                        "Files and paths from the local filesystem"),
                createCategoryMeta("web", "Web Sources", "language", 2,
                        "URLs, web pages, and web content"),
                createCategoryMeta("cloud", "Cloud Storage", "cloud", 3,
                        "Cloud storage services like Google Drive, OneDrive"),
                createCategoryMeta("collaboration", "Collaboration Tools", "groups", 4,
                        "Team tools like Confluence, Jira, Notion, Slack")
        );

        return ResponseEntity.ok(categories);
    }

    private Map<String, Object> createCategoryMeta(String id, String displayName, String icon,
                                                    int order, String description) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("displayName", displayName);
        meta.put("icon", icon);
        meta.put("order", order);
        meta.put("description", description);
        return meta;
    }
}
