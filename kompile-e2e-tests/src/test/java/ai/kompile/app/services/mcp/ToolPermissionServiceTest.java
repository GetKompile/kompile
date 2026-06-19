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

package ai.kompile.app.services.mcp;

import ai.kompile.app.services.mcp.ToolPermissionService.PermissionLevel;
import ai.kompile.app.services.mcp.ToolPermissionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolPermissionService}.
 * Uses a temp directory for config persistence to avoid side effects.
 */
@DisplayName("ToolPermissionService")
class ToolPermissionServiceTest {

    @TempDir
    Path tempDir;

    private ToolPermissionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = createServiceWithTempDir();
    }

    /**
     * Creates a ToolPermissionService that uses tempDir for config storage
     * by overriding the package-private getConfigDir() method.
     */
    private ToolPermissionService createServiceWithTempDir() {
        return new ToolPermissionService() {
            @Override
            Path getConfigDir() {
                return tempDir;
            }
        };
    }

    // =========================================================================
    // isToolAllowed - default behavior
    // =========================================================================

    @Nested
    @DisplayName("isToolAllowed")
    class IsToolAllowed {

        @Test
        @DisplayName("should allow all tools by default")
        void allowsByDefault() {
            assertTrue(service.isToolAllowed("any_tool", "any_category"));
        }

        @Test
        @DisplayName("should allow tools with null category by default")
        void allowsNullCategory() {
            assertTrue(service.isToolAllowed("some_tool", null));
        }

        @Test
        @DisplayName("should deny tool when default is DENY")
        void deniesWhenDefaultIsDeny() {
            service.setDefaultPermission(PermissionLevel.DENY);
            assertFalse(service.isToolAllowed("any_tool", "any_category"));
        }

        @Test
        @DisplayName("should deny tool with null category when default is DENY")
        void deniesNullCategoryWhenDefaultIsDeny() {
            service.setDefaultPermission(PermissionLevel.DENY);
            assertFalse(service.isToolAllowed("some_tool", null));
        }

        @Test
        @DisplayName("should use category rule over default")
        void categoryOverridesDefault() {
            service.setDefaultPermission(PermissionLevel.DENY);
            service.setCategoryRule("rag", PermissionLevel.ALLOW);

            assertTrue(service.isToolAllowed("rag_query", "rag"));
            assertFalse(service.isToolAllowed("write_file", "filesystem"));
        }

        @Test
        @DisplayName("should use tool rule over category rule")
        void toolOverridesCategory() {
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);

            assertTrue(service.isToolAllowed("read_file", "filesystem"));
            assertFalse(service.isToolAllowed("write_file", "filesystem"));
        }

        @Test
        @DisplayName("should use tool rule over default when no category rule")
        void toolOverridesDefault() {
            service.setDefaultPermission(PermissionLevel.DENY);
            service.setToolRule("special_tool", PermissionLevel.ALLOW);

            assertTrue(service.isToolAllowed("special_tool", "system"));
            assertFalse(service.isToolAllowed("other_tool", "system"));
        }

        @Test
        @DisplayName("should handle tool DENY override on ALLOW category")
        void toolDenyOverrideOnAllowCategory() {
            service.setCategoryRule("filesystem", PermissionLevel.ALLOW);
            service.setToolRule("delete_file", PermissionLevel.DENY);

            assertTrue(service.isToolAllowed("read_file", "filesystem"));
            assertFalse(service.isToolAllowed("delete_file", "filesystem"));
        }
    }

    // =========================================================================
    // CRUD operations
    // =========================================================================

    @Nested
    @DisplayName("CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should set and get default permission")
        void setDefaultPermission() {
            assertEquals(PermissionLevel.ALLOW, service.getConfig().getDefaultPermission());
            service.setDefaultPermission(PermissionLevel.DENY);
            assertEquals(PermissionLevel.DENY, service.getConfig().getDefaultPermission());
        }

        @Test
        @DisplayName("should add and remove category rule")
        void categoryRuleCrud() {
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            assertEquals(PermissionLevel.DENY, service.getConfig().getCategoryRules().get("filesystem"));

            service.removeCategoryRule("filesystem");
            assertNull(service.getConfig().getCategoryRules().get("filesystem"));
        }

        @Test
        @DisplayName("should add and remove tool rule")
        void toolRuleCrud() {
            service.setToolRule("read_file", PermissionLevel.DENY);
            assertEquals(PermissionLevel.DENY, service.getConfig().getToolRules().get("read_file"));

            service.removeToolRule("read_file");
            assertNull(service.getConfig().getToolRules().get("read_file"));
        }

        @Test
        @DisplayName("should handle bulk update for categories and tools")
        void bulkUpdate() {
            service.bulkUpdate(
                    Map.of("rag", PermissionLevel.ALLOW, "filesystem", PermissionLevel.DENY),
                    Map.of("read_file", PermissionLevel.ALLOW)
            );

            ToolPermissionConfig config = service.getConfig();
            assertEquals(PermissionLevel.ALLOW, config.getCategoryRules().get("rag"));
            assertEquals(PermissionLevel.DENY, config.getCategoryRules().get("filesystem"));
            assertEquals(PermissionLevel.ALLOW, config.getToolRules().get("read_file"));
        }

        @Test
        @DisplayName("should handle bulk update with null arguments")
        void bulkUpdateNulls() {
            service.setCategoryRule("rag", PermissionLevel.ALLOW);
            service.bulkUpdate(null, null);
            assertEquals(PermissionLevel.ALLOW, service.getConfig().getCategoryRules().get("rag"));
        }

        @Test
        @DisplayName("should overwrite existing category rule")
        void overwriteCategoryRule() {
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setCategoryRule("filesystem", PermissionLevel.ALLOW);
            assertEquals(PermissionLevel.ALLOW, service.getConfig().getCategoryRules().get("filesystem"));
        }

        @Test
        @DisplayName("should overwrite existing tool rule")
        void overwriteToolRule() {
            service.setToolRule("read_file", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);
            assertEquals(PermissionLevel.ALLOW, service.getConfig().getToolRules().get("read_file"));
        }

        @Test
        @DisplayName("removing nonexistent category rule should not throw")
        void removeNonexistentCategoryRule() {
            assertDoesNotThrow(() -> service.removeCategoryRule("nonexistent"));
        }

        @Test
        @DisplayName("removing nonexistent tool rule should not throw")
        void removeNonexistentToolRule() {
            assertDoesNotThrow(() -> service.removeToolRule("nonexistent"));
        }
    }

    // =========================================================================
    // Priority resolution
    // =========================================================================

    @Nested
    @DisplayName("Priority resolution")
    class PriorityResolution {

        @Test
        @DisplayName("full priority chain: tool > category > default")
        void fullPriorityChain() {
            service.setDefaultPermission(PermissionLevel.ALLOW);
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);

            // read_file: tool override ALLOW wins over category DENY
            assertTrue(service.isToolAllowed("read_file", "filesystem"));
            // write_file: category DENY wins over default ALLOW
            assertFalse(service.isToolAllowed("write_file", "filesystem"));
            // rag_query: no category rule, falls to default ALLOW
            assertTrue(service.isToolAllowed("rag_query", "rag"));
        }

        @Test
        @DisplayName("removing tool rule falls back to category")
        void removeToolFallsBackToCategory() {
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);

            assertTrue(service.isToolAllowed("read_file", "filesystem"));

            service.removeToolRule("read_file");
            assertFalse(service.isToolAllowed("read_file", "filesystem"));
        }

        @Test
        @DisplayName("removing category rule falls back to default")
        void removeCategoryFallsBackToDefault() {
            service.setDefaultPermission(PermissionLevel.ALLOW);
            service.setCategoryRule("filesystem", PermissionLevel.DENY);

            assertFalse(service.isToolAllowed("read_file", "filesystem"));

            service.removeCategoryRule("filesystem");
            assertTrue(service.isToolAllowed("read_file", "filesystem"));
        }

        @Test
        @DisplayName("multiple categories are independent")
        void multipleCategoriesIndependent() {
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setCategoryRule("rag", PermissionLevel.ALLOW);

            assertFalse(service.isToolAllowed("write_file", "filesystem"));
            assertTrue(service.isToolAllowed("rag_query", "rag"));
        }
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    @Nested
    @DisplayName("Persistence")
    class PersistenceTests {

        @Test
        @DisplayName("should persist config to JSON file on changes")
        void persistsOnChange() {
            service.setDefaultPermission(PermissionLevel.DENY);
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);

            Path configFile = tempDir.resolve("tool-permissions.json");
            assertTrue(Files.exists(configFile), "Config file should be created");
        }

        @Test
        @DisplayName("should write valid JSON that can be deserialized")
        void writesValidJson() throws Exception {
            service.setDefaultPermission(PermissionLevel.DENY);
            service.setCategoryRule("filesystem", PermissionLevel.DENY);
            service.setToolRule("read_file", PermissionLevel.ALLOW);

            Path configFile = tempDir.resolve("tool-permissions.json");
            String content = Files.readString(configFile);
            ToolPermissionConfig loaded = objectMapper.readValue(content, ToolPermissionConfig.class);

            assertEquals(PermissionLevel.DENY, loaded.getDefaultPermission());
            assertEquals(PermissionLevel.DENY, loaded.getCategoryRules().get("filesystem"));
            assertEquals(PermissionLevel.ALLOW, loaded.getToolRules().get("read_file"));
        }

        @Test
        @DisplayName("should load existing config on initialize")
        void loadExistingConfig() throws Exception {
            // Pre-write a config file
            ToolPermissionConfig config = new ToolPermissionConfig();
            config.setDefaultPermission(PermissionLevel.DENY);
            config.getCategoryRules().put("rag", PermissionLevel.ALLOW);
            config.getToolRules().put("delete_file", PermissionLevel.DENY);

            Path configFile = tempDir.resolve("tool-permissions.json");
            objectMapper.writeValue(configFile.toFile(), config);

            // Create a new service and initialize
            ToolPermissionService svc2 = createServiceWithTempDir();
            svc2.initialize();

            assertEquals(PermissionLevel.DENY, svc2.getConfig().getDefaultPermission());
            assertEquals(PermissionLevel.ALLOW, svc2.getConfig().getCategoryRules().get("rag"));
            assertEquals(PermissionLevel.DENY, svc2.getConfig().getToolRules().get("delete_file"));
        }

        @Test
        @DisplayName("should handle missing config file gracefully on initialize")
        void handleMissingConfig() {
            ToolPermissionService svc2 = createServiceWithTempDir();
            assertDoesNotThrow(() -> svc2.initialize());
            assertEquals(PermissionLevel.ALLOW, svc2.getConfig().getDefaultPermission());
            assertTrue(svc2.getConfig().getCategoryRules().isEmpty());
            assertTrue(svc2.getConfig().getToolRules().isEmpty());
        }

        @Test
        @DisplayName("should handle corrupt config file gracefully")
        void handleCorruptConfig() throws Exception {
            Path configFile = tempDir.resolve("tool-permissions.json");
            Files.writeString(configFile, "{ invalid json }}}");

            ToolPermissionService svc2 = createServiceWithTempDir();
            // Should not throw, should fall back to defaults
            assertDoesNotThrow(() -> svc2.initialize());
            assertEquals(PermissionLevel.ALLOW, svc2.getConfig().getDefaultPermission());
        }

        @Test
        @DisplayName("should persist after each mutation")
        void persistsAfterEachMutation() throws Exception {
            Path configFile = tempDir.resolve("tool-permissions.json");

            service.setCategoryRule("rag", PermissionLevel.DENY);
            assertTrue(Files.exists(configFile));
            String content1 = Files.readString(configFile);
            assertTrue(content1.contains("rag"));

            service.setToolRule("special", PermissionLevel.ALLOW);
            String content2 = Files.readString(configFile);
            assertTrue(content2.contains("special"));
        }
    }

    // =========================================================================
    // ToolPermissionConfig
    // =========================================================================

    @Nested
    @DisplayName("ToolPermissionConfig")
    class ConfigTests {

        @Test
        @DisplayName("default config should have ALLOW default and empty rules")
        void defaultConfig() {
            ToolPermissionConfig config = new ToolPermissionConfig();
            assertEquals(PermissionLevel.ALLOW, config.getDefaultPermission());
            assertNotNull(config.getCategoryRules());
            assertTrue(config.getCategoryRules().isEmpty());
            assertNotNull(config.getToolRules());
            assertTrue(config.getToolRules().isEmpty());
        }

        @Test
        @DisplayName("config should serialize and deserialize correctly")
        void serializeDeserialize() throws Exception {
            ToolPermissionConfig config = new ToolPermissionConfig();
            config.setDefaultPermission(PermissionLevel.DENY);
            config.getCategoryRules().put("filesystem", PermissionLevel.DENY);
            config.getToolRules().put("read_file", PermissionLevel.ALLOW);

            String json = objectMapper.writeValueAsString(config);
            ToolPermissionConfig deserialized = objectMapper.readValue(json, ToolPermissionConfig.class);

            assertEquals(PermissionLevel.DENY, deserialized.getDefaultPermission());
            assertEquals(PermissionLevel.DENY, deserialized.getCategoryRules().get("filesystem"));
            assertEquals(PermissionLevel.ALLOW, deserialized.getToolRules().get("read_file"));
        }
    }
}
