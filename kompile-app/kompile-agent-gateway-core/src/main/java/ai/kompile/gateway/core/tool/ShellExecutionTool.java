/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.gateway.core.tool;

import ai.kompile.gateway.core.service.PermissionService;
import ai.kompile.react.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
public class ShellExecutionTool {

    private final PermissionService permissions;

    public ShellExecutionTool(PermissionService permissions) {
        this.permissions = permissions;
    }

    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("run_command")
                .description("Run a shell command on the host system. Use for file operations, git, and system tasks.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "The command to execute"
                                )
                        ),
                        "required", java.util.List.of("command")
                ))
                .executor(this::execute)
                .requiresApproval(false)
                .parallelizable(false)
                .build();
    }

    private String execute(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "Error: No command provided";
        }

        String safety = permissions.checkCommandSafety(command);
        if ("denied".equals(safety)) {
            return "Permission denied. This command is blocked.";
        }
        if ("needs_approval".equals(safety)) {
            return "Permission pending. This command requires approval. Ask the user to approve: " + command;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try {
                output = new String(
                        process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                );
            } finally {
                // Ensure the process is reaped even if readAllBytes was interrupted
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("Command timed out after 30s, killing: {}", command);
                    process.destroyForcibly();
                }
            }

            int exitCode = process.exitValue();
            String result = output.isBlank() ? "(no output)" : output.trim();

            if (exitCode != 0) {
                result = "Exit code: " + exitCode + "\n" + result;
            }

            log.info("Executed command: {} -> exit code {}", command, exitCode);
            return result;

        } catch (Exception e) {
            log.error("Command execution failed: {}", command, e);
            return "Error executing command: " + e.getMessage();
        }
    }
}
