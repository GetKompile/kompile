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

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing roles - CRUD operations, loading, and assignment.
 * <p>
 * The RoleManager coordinates:
 * - Loading roles from disk (built-in + custom)
 * - Creating/updating/deleting role files
 * - Tracking the currently active role
 * - Reloading roles on changes
 */
public class RoleManager {

    private static final String ROLES_DIR = "roles";

    private final Path workingDirectory;
    private final Map<String, RoleConfig> roles = new ConcurrentHashMap<>();
    private volatile String activeRoleName;

    /**
     * Create a new RoleManager.
     *
     * @param workingDirectory the current working directory (for project-scoped roles)
     */
    public RoleManager(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        loadAllRoles();
    }

    /**
     * Load all roles from built-in defaults, user directory, and project directory.
     * Project roles override user roles, which override built-in roles.
     */
    public synchronized void loadAllRoles() {
        roles.clear();

        // 1. Load built-in roles first
        Map<String, RoleConfig> builtIn = BuiltInRoles.getAll();
        roles.putAll(builtIn);

        // 2. Load user-scoped roles: ~/.kompile/roles/
        Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
        loadFromDirectory(userDir, false);

        // 3. Load project-scoped roles: .kompile/roles/
        Path projectDir = workingDirectory.resolve(".kompile").resolve(ROLES_DIR);
        loadFromDirectory(projectDir, false);
    }

    private void loadFromDirectory(Path dir, boolean isBuiltIn) {
        if (!Files.isDirectory(dir)) return;

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            RoleLoader loader = new RoleLoader(workingDirectory);
                            RoleConfig role = loader.parseRoleFile(file, isBuiltIn);
                            if (role != null) {
                                roles.put(role.getName(), role);
                            }
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to load role from " + file + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }

    /**
     * Get all available roles.
     */
    public List<RoleConfig> getAllRoles() {
        return new ArrayList<>(roles.values());
    }

    /**
     * Get a role by name.
     */
    public RoleConfig getRole(String name) {
        return roles.get(name);
    }

    /**
     * Get role names grouped by category.
     */
    public Map<String, List<String>> getRolesByCategory() {
        return roles.values().stream()
                .collect(Collectors.groupingBy(
                        RoleConfig::getCategory,
                        Collectors.mapping(RoleConfig::getName, Collectors.toList())
                ));
    }

    /**
     * Create a new role and save it to disk.
     *
     * @param name the role name
     * @param displayName the display name
     * @param description the role description
     * @param category the role category
     * @param systemPrompt the system prompt
     * @return the created RoleConfig
     */
    public RoleConfig createRole(String name, String displayName, String description,
                                  String category, String systemPrompt) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name cannot be empty");
        }

        // Normalize name to lowercase, no spaces
        name = name.toLowerCase().replaceAll("[\\s_]+", "-");

        RoleConfig role = RoleConfig.builder()
                .name(name)
                .displayName(displayName != null ? displayName : name)
                .description(description != null ? description : "Custom role: " + name)
                .category(category != null ? category : "general")
                .systemPrompt(systemPrompt != null ? systemPrompt : "")
                .isBuiltIn(false)
                .build();

        saveRole(role);
        roles.put(name, role);
        return role;
    }

    /**
     * Update an existing role.
     *
     * @param name the role name
     * @param displayName the new display name (null to keep existing)
     * @param description the new description (null to keep existing)
     * @param category the new category (null to keep existing)
     * @param systemPrompt the new system prompt (null to keep existing)
     * @return the updated RoleConfig
     */
    public RoleConfig updateRole(String name, String displayName, String description,
                                  String category, String systemPrompt) throws IOException {
        RoleConfig existing = roles.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Role not found: " + name);
        }

        if (existing.isBuiltIn()) {
            throw new IllegalStateException("Cannot modify built-in role: " + name);
        }

        RoleConfig updated = RoleConfig.builder()
                .name(name)
                .displayName(displayName != null ? displayName : existing.getDisplayName())
                .description(description != null ? description : existing.getDescription())
                .category(category != null ? category : existing.getCategory())
                .systemPrompt(systemPrompt != null ? systemPrompt : existing.getSystemPrompt())
                .enabledTools(existing.getEnabledTools())
                .permissionOverrides(existing.getPermissionOverrides())
                .maxSteps(existing.getMaxSteps())
                .canSpawnSubagents(existing.isCanSpawnSubagents())
                .modelHint(existing.getModelHint())
                .sourceFile(existing.getSourceFile())
                .isBuiltIn(false)
                .build();

        saveRole(updated);
        roles.put(name, updated);
        return updated;
    }

    /**
     * Delete a role.
     *
     * @param name the role name
     * @return true if the role was deleted
     */
    public boolean deleteRole(String name) throws IOException {
        RoleConfig role = roles.get(name);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + name);
        }

        if (role.isBuiltIn()) {
            throw new IllegalStateException("Cannot delete built-in role: " + name);
        }

        // Delete the file from disk
        String sourceFile = role.getSourceFile();
        if (sourceFile != null && !sourceFile.isEmpty()) {
            Path rolePath = Path.of(sourceFile);
            if (Files.exists(rolePath)) {
                Files.delete(rolePath);
            }
        } else {
            // Fallback: compute default path
            Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
            Path rolePath = userDir.resolve(name + ".md");
            if (Files.exists(rolePath)) {
                Files.delete(rolePath);
            }
        }

        roles.remove(name);

        // If this was the active role, clear active role
        if (name.equals(activeRoleName)) {
            activeRoleName = null;
        }

        return true;
    }

    /**
     * Save a role to disk.
     */
    private void saveRole(RoleConfig role) throws IOException {
        // Save to user-scoped directory by default
        Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
        Path rolePath = userDir.resolve(role.getName() + ".md");

        RoleLoader.saveRole(role, rolePath);
    }

    // ── Active role tracking ──────────────────────────────────────────────

    /**
     * Get the currently active role name.
     */
    public String getActiveRoleName() {
        return activeRoleName;
    }

    /**
     * Set the active role.
     *
     * @param roleName the role name to activate
     * @return the activated RoleConfig, or null if not found
     */
    public RoleConfig setActiveRole(String roleName) {
        RoleConfig role = roles.get(roleName);
        if (role == null) {
            return null;
        }
        this.activeRoleName = roleName;
        return role;
    }

    /**
     * Clear the active role (revert to default agent).
     */
    public void clearActiveRole() {
        this.activeRoleName = null;
    }

    /**
     * Get the active role config, or null if no role is active.
     */
    public RoleConfig getActiveRole() {
        if (activeRoleName == null) {
            return null;
        }
        return roles.get(activeRoleName);
    }

    /**
     * List all role files (for display).
     */
    public List<Path> listRoleFiles() {
        List<Path> files = new ArrayList<>();

        Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
        if (Files.isDirectory(userDir)) {
            try (var stream = Files.list(userDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .forEach(files::add);
            } catch (IOException e) {
                // Skip
            }
        }

        Path projectDir = workingDirectory.resolve(".kompile").resolve(ROLES_DIR);
        if (Files.isDirectory(projectDir)) {
            try (var stream = Files.list(projectDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .forEach(files::add);
            } catch (IOException e) {
                // Skip
            }
        }

        return files;
    }
}
