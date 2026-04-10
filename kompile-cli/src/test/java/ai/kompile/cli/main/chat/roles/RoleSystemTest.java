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

package ai.kompile.cli.main.chat.roles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RoleConfig, RoleLoader, RoleManager, and BuiltInRoles.
 */
@DisplayName("Role System")
class RoleSystemTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("BuiltInRoles should have default roles")
    void builtInRolesShouldHaveDefaults() {
        Map<String, RoleConfig> roles = BuiltInRoles.getAll();
        
        assertFalse(roles.isEmpty(), "Should have built-in roles");
        assertTrue(roles.containsKey("coder"), "Should have coder role");
        assertTrue(roles.containsKey("architect"), "Should have architect role");
        assertTrue(roles.containsKey("reviewer"), "Should have reviewer role");
        assertTrue(roles.containsKey("researcher"), "Should have researcher role");
        assertTrue(roles.containsKey("devops"), "Should have devops role");
        assertTrue(roles.containsKey("data-scientist"), "Should have data-scientist role");
        
        // Verify all are marked as built-in
        for (RoleConfig role : roles.values()) {
            assertTrue(role.isBuiltIn(), "Role should be built-in: " + role.getName());
        }
    }

    @Test
    @DisplayName("RoleConfig should build correctly")
    void roleConfigShouldBuildCorrectly() {
        RoleConfig role = RoleConfig.builder()
                .name("test-role")
                .displayName("Test Role")
                .category("testing")
                .description("A test role")
                .systemPrompt("You are a test role")
                .maxSteps(25)
                .canSpawnSubagents(false)
                .modelHint("fast")
                .isBuiltIn(false)
                .build();

        assertEquals("test-role", role.getName());
        assertEquals("Test Role", role.getDisplayName());
        assertEquals("testing", role.getCategory());
        assertEquals("A test role", role.getDescription());
        assertEquals("You are a test role", role.getSystemPrompt());
        assertEquals(25, role.getMaxSteps());
        assertFalse(role.isCanSpawnSubagents());
        assertEquals("fast", role.getModelHint());
        assertFalse(role.isBuiltIn());
        assertTrue(role.isCustom());
    }

    @Test
    @DisplayName("RoleConfig should serialize to Markdown")
    void roleConfigShouldSerializeToMarkdown() {
        RoleConfig role = RoleConfig.builder()
                .name("test-role")
                .displayName("Test Role")
                .category("testing")
                .description("A test role")
                .systemPrompt("You are a test role")
                .isBuiltIn(false)
                .build();

        String markdown = role.toMarkdown();
        
        assertNotNull(markdown);
        assertTrue(markdown.startsWith("---"), "Should start with frontmatter");
        assertTrue(markdown.contains("name: test-role"), "Should contain name");
        assertTrue(markdown.contains("display_name: Test Role"), "Should contain display name");
        assertTrue(markdown.contains("category: testing"), "Should contain category");
        assertTrue(markdown.contains("You are a test role"), "Should contain system prompt");
    }

    @Test
    @DisplayName("RoleLoader should parse role files")
    void roleLoaderShouldParseRoleFiles() throws IOException {
        // Create a test role file
        String content = """
                ---
                name: test-role
                display_name: Test Role
                category: testing
                description: A test role for parsing
                model: fast
                max_steps: 20
                can_spawn: false
                ---
                You are a test role for parsing frontmatter and body.
                """;

        Path roleFile = tempDir.resolve("test-role.md");
        Files.writeString(roleFile, content);

        RoleLoader loader = new RoleLoader(tempDir);
        RoleConfig role = loader.parseRoleFile(roleFile, false);

        assertNotNull(role);
        assertEquals("test-role", role.getName());
        assertEquals("Test Role", role.getDisplayName());
        assertEquals("testing", role.getCategory());
        assertEquals("A test role for parsing", role.getDescription());
        assertEquals("fast", role.getModelHint());
        assertEquals(20, role.getMaxSteps());
        assertFalse(role.isCanSpawnSubagents());
        assertTrue(role.getSystemPrompt().contains("test role for parsing"));
    }

    @Test
    @DisplayName("RoleManager should load and manage roles")
    void roleManagerShouldLoadAndManageRoles() {
        RoleManager manager = new RoleManager(tempDir);
        
        // Should have built-in roles
        List<RoleConfig> roles = manager.getAllRoles();
        assertFalse(roles.isEmpty(), "Should have roles");
        
        // Should be able to get a role by name
        RoleConfig coderRole = manager.getRole("coder");
        assertNotNull(coderRole, "Should get coder role");
        assertEquals("coder", coderRole.getName());
    }

    @Test
    @DisplayName("RoleManager should create custom roles")
    void roleManagerShouldCreateCustomRoles() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        
        RoleConfig role = manager.createRole(
                "custom-role",
                "Custom Role",
                "A custom role",
                "testing",
                "You are a custom role"
        );

        assertNotNull(role);
        assertEquals("custom-role", role.getName());
        assertEquals("Custom Role", role.getDisplayName());
        assertEquals("testing", role.getCategory());
        assertFalse(role.isBuiltIn());
        assertTrue(role.isCustom());

        // Should be retrievable
        RoleConfig retrieved = manager.getRole("custom-role");
        assertNotNull(retrieved);
        assertEquals("custom-role", retrieved.getName());
    }

    @Test
    @DisplayName("RoleManager should update custom roles")
    void roleManagerShouldUpdateCustomRoles() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        
        // Create a role
        manager.createRole(
                "update-test",
                "Update Test",
                "Original description",
                "testing",
                "Original prompt"
        );

        // Update it
        RoleConfig updated = manager.updateRole(
                "update-test",
                "Updated Test",
                "Updated description",
                "dev",
                "Updated prompt"
        );

        assertEquals("Updated Test", updated.getDisplayName());
        assertEquals("Updated description", updated.getDescription());
        assertEquals("dev", updated.getCategory());
        assertEquals("Updated prompt", updated.getSystemPrompt());
    }

    @Test
    @DisplayName("RoleManager should delete custom roles")
    void roleManagerShouldDeleteCustomRoles() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        
        // Create a role
        manager.createRole(
                "delete-test",
                "Delete Test",
                "To be deleted",
                "testing",
                "Delete me"
        );

        // Should exist
        assertNotNull(manager.getRole("delete-test"));

        // Delete it
        boolean deleted = manager.deleteRole("delete-test");
        assertTrue(deleted);

        // Should no longer exist
        assertNull(manager.getRole("delete-test"));
    }

    @Test
    @DisplayName("RoleManager should not modify built-in roles")
    void roleManagerShouldNotModifyBuiltInRoles() {
        RoleManager manager = new RoleManager(tempDir);
        
        // Should not be able to update built-in roles
        assertThrows(IllegalStateException.class, () -> {
            manager.updateRole("coder", "Updated Coder", null, null, null);
        });

        // Should not be able to delete built-in roles
        assertThrows(IllegalStateException.class, () -> {
            manager.deleteRole("coder");
        });
    }

    @Test
    @DisplayName("RoleManager should track active role")
    void roleManagerShouldTrackActiveRole() {
        RoleManager manager = new RoleManager(tempDir);
        
        // Initially no active role
        assertNull(manager.getActiveRoleName());
        assertNull(manager.getActiveRole());

        // Set active role
        RoleConfig role = manager.setActiveRole("coder");
        assertNotNull(role);
        assertEquals("coder", manager.getActiveRoleName());
        assertEquals("coder", manager.getActiveRole().getName());

        // Clear active role
        manager.clearActiveRole();
        assertNull(manager.getActiveRoleName());
    }

    @Test
    @DisplayName("RoleConfig should create AgentConfig")
    void roleConfigShouldCreateAgentConfig() {
        RoleConfig role = RoleConfig.builder()
                .name("test-role")
                .displayName("Test Role")
                .systemPrompt("You are a test")
                .maxSteps(30)
                .build();

        var agentConfig = role.toAgentConfig();
        
        assertNotNull(agentConfig);
        assertEquals("test-role", agentConfig.getName());
        assertEquals("Test Role", agentConfig.getDisplayName());
        assertEquals("You are a test", agentConfig.getSystemPrompt());
        assertEquals(30, agentConfig.getMaxSteps());
    }

    @Test
    @DisplayName("RoleLoader should save and load roles")
    void roleLoaderShouldSaveAndLoadRoles() throws IOException {
        RoleConfig role = RoleConfig.builder()
                .name("save-test")
                .displayName("Save Test")
                .category("testing")
                .description("Test saving")
                .systemPrompt("You are a save test")
                .isBuiltIn(false)
                .build();

        Path savePath = tempDir.resolve("save-test.md");
        RoleLoader.saveRole(role, savePath);

        // Verify file exists
        assertTrue(Files.exists(savePath));

        // Load it back
        RoleLoader loader = new RoleLoader(tempDir);
        RoleConfig loaded = loader.parseRoleFile(savePath, false);

        assertNotNull(loaded);
        assertEquals("save-test", loaded.getName());
        assertEquals("Save Test", loaded.getDisplayName());
        assertEquals("Test saving", loaded.getDescription());
    }
}
