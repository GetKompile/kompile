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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.tools;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.core.source.provider.SourceProviderDto;
import ai.kompile.core.source.provider.SourceProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceManagementToolTest {

    private final SourceProviderRegistry registry = new SourceProviderRegistry(List.of(
            provider("web", "Web", "web", 1, true),
            provider("zulu", "Zulu", "local", 2, true),
            provider("alpha", "Alpha", "local", 2, true),
            provider("first", "First", "local", 1, true),
            provider("offline", "Offline", "cloud", 1, false)));

    private SourceManagementTool tool() {
        registry.init();
        return new SourceManagementTool(null, registry, null);
    }

    @Test
    void listsAvailableProvidersByDefaultWithCountsAndStableOrdering() {
        SourceManagementTool tool = tool();
        for (Boolean includeUnavailable : new Boolean[]{null, false}) {
            Map<?, ?> data = assertInstanceOf(Map.class, successData(tool.getSourceProviders(
                    new SourceManagementTool.GetSourceProvidersInput(includeUnavailable))));
            assertEquals(Set.of("providers", "totalCount", "availableCount"), data.keySet());
            assertEquals(5, data.get("totalCount"));
            assertEquals(4, data.get("availableCount"));
            assertEquals(List.of("first", "alpha", "zulu", "web"), ids(data.get("providers")));
        }
    }

    @Test
    void includesUnavailableProvidersAndTheirDetailsWhenRequested() {
        Map<?, ?> data = assertInstanceOf(Map.class, successData(tool().getSourceProviders(
                new SourceManagementTool.GetSourceProvidersInput(true))));
        assertEquals(List.of("offline", "first", "alpha", "zulu", "web"), ids(data.get("providers")));
        assertEquals(5, data.get("totalCount"));
        assertEquals(4, data.get("availableCount"));
        SourceProviderDto offline = assertInstanceOf(SourceProviderDto.class,
                assertInstanceOf(List.class, data.get("providers")).get(0));
        assertFalse(offline.isAvailable());
        assertEquals("Not configured", offline.getUnavailableReason());
    }

    @Test
    void groupsOnlyAvailableProvidersWithoutChangingCategoryOrProviderOrder() {
        Map<?, ?> data = assertInstanceOf(Map.class, successData(tool().getSourceProvidersByCategory(
                new SourceManagementTool.GetSourceProvidersByCategoryInput())));
        assertEquals(List.of("local", "web"), List.copyOf(data.keySet()));
        assertEquals(List.of("first", "alpha", "zulu"), ids(data.get("local")));
        assertEquals(List.of("web"), ids(data.get("web")));
    }

    @Test
    void getsAvailableAndUnavailableProvidersAsDtos() {
        SourceManagementTool tool = tool();
        for (String id : List.of("first", "offline")) {
            SourceProviderDto actual = assertInstanceOf(SourceProviderDto.class, successData(
                    tool.getSourceProvider(new SourceManagementTool.GetSourceProviderInput(id))));
            assertEquals(SourceProviderDto.fromProvider(registry.getProvider(id)), actual);
        }
    }

    @Test
    void rejectsMissingAndUnknownProviderIdsWithoutNullResponseBodies() {
        SourceManagementTool tool = tool();
        assertEquals(error("Provider ID is required"), tool.getSourceProvider(
                new SourceManagementTool.GetSourceProviderInput(null)));
        for (String id : List.of("missing", "", " ")) {
            assertEquals(error("Source provider not found: " + id), tool.getSourceProvider(
                    new SourceManagementTool.GetSourceProviderInput(id)));
        }
    }

    @Test
    void preservesCategoryMetadataResponse() {
        assertEquals(List.of(
                category("local", "Local Sources", "computer", 1, "Files and paths from the local filesystem"),
                category("web", "Web Sources", "language", 2, "URLs, web pages, and web content"),
                category("cloud", "Cloud Storage", "cloud", 3, "Cloud storage services like Google Drive, OneDrive"),
                category("collaboration", "Collaboration Tools", "groups", 4,
                        "Installed team sources such as Confluence, Slack, Discord, and email")),
                successData(tool().getSourceProviderCategories(
                        new SourceManagementTool.GetSourceProviderCategoriesInput())));
    }

    @Test
    void missingRegistryReturnsExistingUnavailableErrorForEveryProviderOperation() {
        SourceManagementTool tool = new SourceManagementTool(null, null, null);
        Map<String, Object> expected = error("Source provider registry not available");
        assertEquals(expected, tool.getSourceProviders(new SourceManagementTool.GetSourceProvidersInput(true)));
        assertEquals(expected, tool.getSourceProvidersByCategory(new SourceManagementTool.GetSourceProvidersByCategoryInput()));
        assertEquals(expected, tool.getSourceProvider(new SourceManagementTool.GetSourceProviderInput(null)));
        assertEquals(expected, tool.getSourceProviderCategories(new SourceManagementTool.GetSourceProviderCategoriesInput()));
    }

    @Test
    void emptyRegistryReturnsEmptyCollectionsAndZeroCounts() {
        SourceManagementTool tool = new SourceManagementTool(null, new SourceProviderRegistry(List.of()), null);
        assertEquals(Map.of("providers", List.of(), "totalCount", 0, "availableCount", 0),
                successData(tool.getSourceProviders(new SourceManagementTool.GetSourceProvidersInput(true))));
        assertEquals(Map.of(), successData(tool.getSourceProvidersByCategory(
                new SourceManagementTool.GetSourceProvidersByCategoryInput())));
    }

    @Test
    void registryFailureUsesToolErrorEnvelope() {
        SourceProviderRegistry failed = mock(SourceProviderRegistry.class);
        when(failed.getAvailableProviders()).thenThrow(new IllegalStateException("Registry failure"));
        SourceManagementTool tool = new SourceManagementTool(null, failed, null);
        assertEquals(error("Registry failure"), tool.getSourceProviders(
                new SourceManagementTool.GetSourceProvidersInput(false)));
    }

    private static Object successData(Map<String, Object> result) {
        assertEquals(Set.of("status", "data"), result.keySet());
        assertEquals("success", result.get("status"));
        return result.get("data");
    }

    private static List<String> ids(Object providers) {
        List<?> values = assertInstanceOf(List.class, providers);
        return values.stream().map(p -> assertInstanceOf(SourceProviderDto.class, p).getId()).toList();
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "error", message);
    }

    private static Map<String, Object> category(String id, String name, String icon, int order, String description) {
        return Map.of("id", id, "displayName", name, "icon", icon, "order", order, "description", description);
    }

    private static SourceProvider provider(String id, String name, String category, int order, boolean available) {
        return new SourceProvider() {
            public String getId() { return id; }
            public String getDisplayName() { return name; }
            public String getDescription() { return "Test source"; }
            public String getIcon() { return "description"; }
            public String getCategory() { return category; }
            public int getOrder() { return order; }
            public boolean isAvailable() { return available; }
            public String getUnavailableReason() { return available ? null : "Not configured"; }
            public List<SourceFormField> getFormFields() { return List.of(); }
        };
    }
}
