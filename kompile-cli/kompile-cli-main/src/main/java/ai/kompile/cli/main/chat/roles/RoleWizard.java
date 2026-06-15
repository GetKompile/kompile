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

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.List;

/**
 * Interactive TUI wizard for managing chat roles.
 * <p>
 * Triggered by the /roles command in the chat REPL.
 * Provides a menu-driven interface for:
 * - Listing all roles
 * - Creating new roles
 * - Editing existing roles
 * - Deleting roles
 * - Assigning a role to the current agent
 */
public class RoleWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";

    private final RoleManager roleManager;

    public RoleWizard(RoleManager roleManager) {
        this.roleManager = roleManager;
    }

    /**
     * Run the interactive role management wizard.
     */
    public void run() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            boolean exit = false;
            while (!exit) {
                printHeader();
                printMenu();

                String input = prompt(reader, "\nChoice [1-7]: ");
                if (input == null || input.isBlank()) continue;

                String trimmed = input.trim();
                exit = handleChoice(trimmed, reader);
            }
        } catch (Exception e) {
            System.err.println("Role wizard error: " + e.getMessage());
        }
    }

    private void printHeader() {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + CYAN + "  │       Role Manager                    │" + RESET);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println();

        String activeRole = roleManager.getActiveRoleName();
        if (activeRole != null) {
            System.out.println("  Active role: " + GREEN + activeRole + RESET);
        } else {
            System.out.println("  Active role: " + DIM + "(none - using default agent)" + RESET);
        }
        System.out.println();
    }

    private void printMenu() {
        System.out.println(BOLD + "  Menu:" + RESET);
        System.out.println();
        System.out.println("  " + CYAN + "1" + RESET + "  List all roles");
        System.out.println("  " + CYAN + "2" + RESET + "  Create new role");
        System.out.println("  " + CYAN + "3" + RESET + "  Edit existing role");
        System.out.println("  " + CYAN + "4" + RESET + "  Delete role");
        System.out.println("  " + CYAN + "5" + RESET + "  Assign role to current agent");
        System.out.println("  " + CYAN + "6" + RESET + "  View role details");
        System.out.println("  " + CYAN + "7" + RESET + "  Exit");
        System.out.println();
    }

    private boolean handleChoice(String choice, LineReader reader) {
        return switch (choice) {
            case "1" -> {
                listRoles();
                yield false;
            }
            case "2" -> {
                createRole(reader);
                yield false;
            }
            case "3" -> {
                editRole(reader);
                yield false;
            }
            case "4" -> {
                deleteRole(reader);
                yield false;
            }
            case "5" -> {
                assignRole(reader);
                yield false;
            }
            case "6" -> {
                viewRoleDetails(reader);
                yield false;
            }
            case "7" -> true;
            default -> {
                System.out.println("  " + YELLOW + "Invalid choice. Please enter 1-7." + RESET);
                yield false;
            }
        };
    }

    private void listRoles() {
        List<RoleConfig> roles = roleManager.getAllRoles();
        var byCategory = roleManager.getRolesByCategory();
        String activeRole = roleManager.getActiveRoleName();

        System.out.println(BOLD + "\n  Available Roles:" + RESET);
        System.out.println();

        for (var entry : byCategory.entrySet()) {
            System.out.println("  " + BOLD + "[" + entry.getKey() + "]" + RESET);
            for (String roleName : entry.getValue()) {
                RoleConfig role = roleManager.getRole(roleName);
                if (role != null) {
                    String activeMarker = roleName.equals(activeRole) ? GREEN + " [ACTIVE]" + RESET : "";
                    String builtInMarker = role.isBuiltIn() ? DIM + " (built-in)" + RESET : "";
                    System.out.println("    " + CYAN + roleName + RESET + activeMarker + builtInMarker);
                    System.out.println("      " + role.getDescription());
                }
            }
            System.out.println();
        }

        System.out.println("  Total: " + roles.size() + " roles");
    }

    private void createRole(LineReader reader) {
        System.out.println(BOLD + "\n  Create New Role" + RESET);
        System.out.println();

        String name = prompt(reader, "  Role name (e.g., java-expert): ");
        if (name == null || name.isBlank()) return;

        String displayName = prompt(reader, "  Display name (e.g., Java Expert): ");
        if (displayName == null || displayName.isBlank()) {
            displayName = name;
        }

        String description = prompt(reader, "  Description: ");
        if (description == null || description.isBlank()) {
            description = "Custom role: " + name;
        }

        System.out.println("\n  Categories: development, research, devops, data, general");
        String category = prompt(reader, "  Category [general]: ");
        if (category == null || category.isBlank()) {
            category = "general";
        }

        System.out.println("\n  Enter system prompt (define the role's behavior, expertise, guidelines):");
        System.out.println("  " + DIM + "(Enter a single line, or multiple lines ending with empty line)" + RESET);
        System.out.println("  " + DIM + "(You can also edit the file directly later at ~/.kompile/roles/" + name + ".md)" + RESET);
        System.out.println();

        StringBuilder promptBuilder = new StringBuilder();
        while (true) {
            String line = prompt(reader, "  > ");
            if (line == null || line.isBlank()) {
                if (promptBuilder.length() > 0) break;
                continue;
            }
            if (promptBuilder.length() > 0) {
                promptBuilder.append("\n");
            }
            promptBuilder.append(line);
        }

        String systemPrompt = promptBuilder.toString();
        if (systemPrompt.isBlank()) {
            systemPrompt = "You are a specialized assistant for " + description + ".";
        }

        try {
            RoleConfig role = roleManager.createRole(name, displayName, description, category, systemPrompt);
            System.out.println();
            System.out.println(GREEN + "  ✓ Role created successfully!" + RESET);
            System.out.println("  Name: " + BOLD + role.getName() + RESET);
            System.out.println("  Saved to: " + DIM + role.getSourceFile() + RESET);
            System.out.println();
            System.out.println("  Use option 5 to activate this role.");
        } catch (IOException e) {
            System.err.println("  " + RED + "Failed to create role: " + e.getMessage() + RESET);
        }
    }

    private void editRole(LineReader reader) {
        System.out.println(BOLD + "\n  Edit Role" + RESET);
        System.out.println();

        List<RoleConfig> roles = roleManager.getAllRoles();
        List<RoleConfig> customRoles = roles.stream()
                .filter(RoleConfig::isCustom)
                .toList();

        if (customRoles.isEmpty()) {
            System.out.println("  " + YELLOW + "No custom roles available to edit." + RESET);
            System.out.println("  " + DIM + "(Built-in roles cannot be edited. Create a new role instead.)" + RESET);
            return;
        }

        System.out.println("  Available custom roles:");
        System.out.println();
        for (int i = 0; i < customRoles.size(); i++) {
            RoleConfig role = customRoles.get(i);
            System.out.println("  " + CYAN + (i + 1) + RESET + "  " + role.getName() + DIM + " - " + role.getDescription() + RESET);
        }
        System.out.println();

        String input = prompt(reader, "  Select role (number or name): ");
        if (input == null || input.isBlank()) return;

        RoleConfig selectedRole = null;
        try {
            int idx = Integer.parseInt(input.trim()) - 1;
            if (idx >= 0 && idx < customRoles.size()) {
                selectedRole = customRoles.get(idx);
            }
        } catch (NumberFormatException e) {
            selectedRole = roleManager.getRole(input.trim());
        }

        if (selectedRole == null) {
            System.out.println("  " + YELLOW + "Role not found." + RESET);
            return;
        }

        if (selectedRole.isBuiltIn()) {
            System.out.println("  " + YELLOW + "Cannot edit built-in role. Create a new role instead." + RESET);
            return;
        }

        System.out.println("\n  Editing role: " + BOLD + selectedRole.getName() + RESET);
        System.out.println("  " + DIM + "(Press Enter to keep current value)" + RESET);
        System.out.println();

        String displayName = prompt(reader, "  Display name [" + selectedRole.getDisplayName() + "]: ");
        String description = prompt(reader, "  Description [" + selectedRole.getDescription() + "]: ");

        System.out.println("\n  Categories: development, research, devops, data, general");
        String category = prompt(reader, "  Category [" + selectedRole.getCategory() + "]: ");

        System.out.println("\n  Current system prompt:");
        System.out.println(DIM + "  ┌─────────────────────────────────────────" + RESET);
        String[] lines = selectedRole.getSystemPrompt().split("\n");
        for (int i = 0; i < Math.min(10, lines.length); i++) {
            System.out.println(DIM + "  │ " + RESET + lines[i]);
        }
        if (lines.length > 10) {
            System.out.println(DIM + "  │ ... (" + (lines.length - 10) + " more lines)" + RESET);
        }
        System.out.println(DIM + "  └─────────────────────────────────────────" + RESET);
        System.out.println();

        String editChoice = prompt(reader, "  Edit system prompt? [y/N]: ");
        String systemPrompt = null;
        if (editChoice != null && editChoice.toLowerCase().startsWith("y")) {
            System.out.println("\n  Enter new system prompt (empty line to finish):");
            System.out.println();

            StringBuilder promptBuilder = new StringBuilder();
            while (true) {
                String line = prompt(reader, "  > ");
                if (line == null || line.isBlank()) {
                    if (promptBuilder.length() > 0) break;
                    continue;
                }
                if (promptBuilder.length() > 0) {
                    promptBuilder.append("\n");
                }
                promptBuilder.append(line);
            }
            systemPrompt = promptBuilder.toString();
        }

        try {
            RoleConfig updated = roleManager.updateRole(
                    selectedRole.getName(),
                    displayName != null && !displayName.isBlank() ? displayName : null,
                    description != null && !description.isBlank() ? description : null,
                    category != null && !category.isBlank() ? category : null,
                    systemPrompt
            );
            System.out.println();
            System.out.println(GREEN + "  ✓ Role updated successfully!" + RESET);
        } catch (IOException e) {
            System.err.println("  " + RED + "Failed to update role: " + e.getMessage() + RESET);
        }
    }

    private void deleteRole(LineReader reader) {
        System.out.println(BOLD + "\n  Delete Role" + RESET);
        System.out.println();

        List<RoleConfig> roles = roleManager.getAllRoles();
        List<RoleConfig> customRoles = roles.stream()
                .filter(RoleConfig::isCustom)
                .toList();

        if (customRoles.isEmpty()) {
            System.out.println("  " + YELLOW + "No custom roles available to delete." + RESET);
            System.out.println("  " + DIM + "(Built-in roles cannot be deleted.)" + RESET);
            return;
        }

        System.out.println("  Available custom roles:");
        System.out.println();
        for (int i = 0; i < customRoles.size(); i++) {
            RoleConfig role = customRoles.get(i);
            System.out.println("  " + CYAN + (i + 1) + RESET + "  " + role.getName() + DIM + " - " + role.getDescription() + RESET);
        }
        System.out.println();

        String input = prompt(reader, "  Select role to delete (number or name): ");
        if (input == null || input.isBlank()) return;

        RoleConfig selectedRole = null;
        try {
            int idx = Integer.parseInt(input.trim()) - 1;
            if (idx >= 0 && idx < customRoles.size()) {
                selectedRole = customRoles.get(idx);
            }
        } catch (NumberFormatException e) {
            selectedRole = roleManager.getRole(input.trim());
        }

        if (selectedRole == null) {
            System.out.println("  " + YELLOW + "Role not found." + RESET);
            return;
        }

        if (selectedRole.isBuiltIn()) {
            System.out.println("  " + YELLOW + "Cannot delete built-in role." + RESET);
            return;
        }

        String confirm = prompt(reader, "  Delete role '" + selectedRole.getName() + "'? [y/N]: ");
        if (confirm == null || !confirm.toLowerCase().startsWith("y")) {
            System.out.println("  " + DIM + "Cancelled." + RESET);
            return;
        }

        try {
            roleManager.deleteRole(selectedRole.getName());
            System.out.println();
            System.out.println(GREEN + "  ✓ Role deleted: " + selectedRole.getName() + RESET);
        } catch (IOException e) {
            System.err.println("  " + RED + "Failed to delete role: " + e.getMessage() + RESET);
        }
    }

    private void assignRole(LineReader reader) {
        System.out.println(BOLD + "\n  Assign Role to Current Agent" + RESET);
        System.out.println();

        List<RoleConfig> roles = roleManager.getAllRoles();
        var byCategory = roleManager.getRolesByCategory();

        System.out.println("  Available roles:");
        System.out.println();
        int idx = 1;
        for (var entry : byCategory.entrySet()) {
            System.out.println("  " + BOLD + "[" + entry.getKey() + "]" + RESET);
            for (String roleName : entry.getValue()) {
                RoleConfig role = roleManager.getRole(roleName);
                if (role != null) {
                    System.out.println("    " + CYAN + idx + RESET + "  " + roleName + DIM + " - " + role.getDescription() + RESET);
                    idx++;
                }
            }
            System.out.println();
        }

        String input = prompt(reader, "  Select role (number or name): ");
        if (input == null || input.isBlank()) return;

        RoleConfig selectedRole = null;
        try {
            int choice = Integer.parseInt(input.trim());
            int current = 1;
            for (var entry : byCategory.entrySet()) {
                for (String roleName : entry.getValue()) {
                    if (current == choice) {
                        selectedRole = roleManager.getRole(roleName);
                        break;
                    }
                    current++;
                }
                if (selectedRole != null) break;
            }
        } catch (NumberFormatException e) {
            selectedRole = roleManager.getRole(input.trim());
        }

        if (selectedRole == null) {
            System.out.println("  " + YELLOW + "Role not found." + RESET);
            return;
        }

        roleManager.setActiveRole(selectedRole.getName());
        System.out.println();
        System.out.println(GREEN + "  ✓ Role activated: " + selectedRole.getName() + RESET);
        System.out.println("  " + selectedRole.getDisplayName() + DIM + " - " + selectedRole.getDescription() + RESET);
        System.out.println();
        System.out.println("  The agent will now use this role's system prompt.");
    }

    private void viewRoleDetails(LineReader reader) {
        System.out.println(BOLD + "\n  View Role Details" + RESET);
        System.out.println();

        List<RoleConfig> roles = roleManager.getAllRoles();

        System.out.println("  All roles:");
        System.out.println();
        for (int i = 0; i < roles.size(); i++) {
            RoleConfig role = roles.get(i);
            String activeMarker = role.getName().equals(roleManager.getActiveRoleName()) ? GREEN + " [ACTIVE]" + RESET : "";
            String builtInMarker = role.isBuiltIn() ? DIM + " (built-in)" + RESET : "";
            System.out.println("  " + CYAN + (i + 1) + RESET + "  " + role.getName() + activeMarker + builtInMarker + DIM + " - " + role.getDescription() + RESET);
        }
        System.out.println();

        String input = prompt(reader, "  Select role (number or name): ");
        if (input == null || input.isBlank()) return;

        RoleConfig selectedRole = null;
        try {
            int idx = Integer.parseInt(input.trim()) - 1;
            if (idx >= 0 && idx < roles.size()) {
                selectedRole = roles.get(idx);
            }
        } catch (NumberFormatException e) {
            selectedRole = roleManager.getRole(input.trim());
        }

        if (selectedRole == null) {
            System.out.println("  " + YELLOW + "Role not found." + RESET);
            return;
        }

        System.out.println();
        System.out.println(BOLD + "  Role: " + RESET + selectedRole.getName());
        System.out.println(BOLD + "  Display Name: " + RESET + selectedRole.getDisplayName());
        System.out.println(BOLD + "  Category: " + RESET + selectedRole.getCategory());
        System.out.println(BOLD + "  Description: " + RESET + selectedRole.getDescription());
        System.out.println(BOLD + "  Max Steps: " + RESET + selectedRole.getMaxSteps());
        System.out.println(BOLD + "  Can Spawn Subagents: " + RESET + selectedRole.isCanSpawnSubagents());
        System.out.println(BOLD + "  Model Hint: " + RESET + selectedRole.getModelHint());
        System.out.println(BOLD + "  Tools: " + RESET + (selectedRole.getEnabledTools().contains("*") ? "all" : selectedRole.getEnabledTools()));
        if (selectedRole.getSourceFile() != null && !selectedRole.getSourceFile().isEmpty()) {
            System.out.println(BOLD + "  Source: " + RESET + selectedRole.getSourceFile());
        }
        System.out.println();
        System.out.println(BOLD + "  System Prompt:" + RESET);
        System.out.println(DIM + "  ┌─────────────────────────────────────────" + RESET);
        for (String line : selectedRole.getSystemPrompt().split("\n")) {
            System.out.println(DIM + "  │ " + RESET + line);
        }
        System.out.println(DIM + "  └─────────────────────────────────────────" + RESET);
    }

    private static String prompt(LineReader reader, String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (Exception e) {
            return null;
        }
    }
}
