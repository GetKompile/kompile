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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.ProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Execute shell commands via bash with command-level permission classification.
 *
 * Commands are parsed and classified into risk tiers that map to permission keys
 * in the existing {@link ai.kompile.cli.main.chat.permission.PermissionService}:
 *
 * <ul>
 *   <li>{@code bash.readonly} — read-only commands (ls, cat, git status, etc.) — default ALLOW</li>
 *   <li>{@code bash.write} — commands that modify files/state (mvn, npm, git commit, etc.) — default ASK</li>
 *   <li>{@code bash.destructive} — dangerous commands (rm -rf, git push --force, etc.) — default ASK</li>
 * </ul>
 *
 * The permission prompt shows the actual command so the user knows what they are approving.
 * Session-level "always" / "never" choices are remembered by PermissionService.
 */
public class BashTool implements CliTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    // --- Permission key constants ---
    public static final String PERM_READONLY = "bash.readonly";
    public static final String PERM_WRITE = "bash.write";
    public static final String PERM_DESTRUCTIVE = "bash.destructive";

    // --- Command classification patterns ---

    /** Commands that are always safe (read-only). */
    private static final Set<String> READONLY_COMMANDS = Set.of(
            "ls", "ll", "dir", "cat", "head", "tail", "less", "more",
            "find", "locate", "which", "whereis", "whatis", "type",
            "wc", "diff", "file", "stat", "du", "df",
            "pwd", "whoami", "hostname", "uname", "date", "uptime",
            "env", "printenv", "echo", "printf",
            "ps", "top", "htop", "free", "lscpu", "lsblk",
            "id", "groups", "who", "w",
            "tree", "rg", "grep", "egrep", "fgrep", "ag", "ack",
            "jq", "xmllint", "python3", "python", "node", "java"
    );

    /** Git subcommands that are read-only. */
    private static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "branch", "tag",
            "describe", "remote", "stash list", "shortlog",
            "blame", "ls-files", "ls-tree", "rev-parse", "rev-list",
            "config --get", "config --list", "reflog"
    );

    /** Git subcommands that are destructive. */
    private static final Set<String> GIT_DESTRUCTIVE_SUBCOMMANDS = Set.of(
            "push --force", "push -f", "reset --hard",
            "clean -f", "clean -fd", "clean -fdx",
            "branch -D", "branch --delete --force"
    );

    /** Top-level commands that are inherently destructive. */
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
            "rmdir", "shred", "dd", "mkfs", "fdisk",
            "kill", "killall", "pkill",
            "shutdown", "reboot", "halt", "poweroff",
            "systemctl", "service",
            "iptables", "firewall-cmd",
            "useradd", "userdel", "usermod", "passwd",
            "chown", "chmod"
    );

    /** Patterns that indicate destructive intent regardless of command. */
    private static final List<Pattern> DESTRUCTIVE_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|--recursive|--force)"),  // rm -rf, rm -fr, etc.
            Pattern.compile("rm\\s+-[a-zA-Z]*f"),                                    // rm -f
            Pattern.compile(">(\\s*/dev/|\\s*/)"),                                   // redirect to /dev/ or /
            Pattern.compile(":\\s*>"),                                                // : > file (truncate)
            Pattern.compile("\\|\\s*sudo"),                                          // pipe to sudo
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),                             // curl | sh
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),                             // wget | sh
            Pattern.compile("git\\s+push\\s+.*--force"),                             // git push --force
            Pattern.compile("git\\s+reset\\s+--hard"),                               // git reset --hard
            Pattern.compile("docker\\s+(rm|rmi|system\\s+prune)"),                   // docker destructive
            Pattern.compile("npm\\s+publish"),                                       // npm publish
            Pattern.compile("mvn\\s+deploy")                                         // mvn deploy
    );

    /** Commands that modify files/state but aren't destructive. */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "mkdir", "touch", "cp", "mv", "ln",
            "sed", "awk", "tee", "patch",
            "tar", "zip", "unzip", "gzip", "gunzip",
            "pip", "pip3", "npm", "npx", "yarn", "pnpm", "bun",
            "mvn", "gradle", "make", "cmake", "cargo",
            "docker", "docker-compose", "podman",
            "git", "gh", "curl", "wget", "ssh", "scp", "rsync"
    );

    @Override
    public String id() { return "bash"; }

    @Override
    public String description() {
        return "Execute a shell command via bash and return its output (stdout + stderr). " +
                "Commands run in the project working directory. Default timeout is 120 seconds. " +
                "Commands are classified by risk level: read-only commands run freely, " +
                "write commands and destructive commands require user approval. " +
                "Prefer dedicated tools (read, grep, glob) over bash equivalents (cat, grep, find) " +
                "when available.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode command = props.putObject("command");
        command.put("type", "string");
        command.put("description", "The bash command to execute");

        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "integer");
        timeout.put("description", "Timeout in seconds (default: 120, max: 600)");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "Brief description of what this command does");

        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public String permissionKey() {
        // Base key — actual checks use the classified key
        return "bash";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String command = params.path("command").asText("");
        int timeout = params.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
        String desc = params.path("description").asText("");

        if (command.isEmpty()) {
            return ToolResult.error("command is required");
        }

        timeout = Math.min(timeout, MAX_TIMEOUT_SECONDS);

        // Classify the command and check the appropriate permission
        CommandRisk risk = classifyCommand(command);
        String permKey = risk.permissionKey;
        String permDesc = risk.label + ": " + command;
        if (!desc.isEmpty()) {
            permDesc = desc + " [" + risk.label + "]: " + command;
        }
        context.checkPermission(permKey, permDesc);

        Path workDir = context.getWorkingDirectory();

        // Execute via ProcessManager
        ProcessManager.ProcessResult result = ProcessManager.execute(
                command, workDir, timeout * 1000, context.getAbortSignal());

        String outputStr = result.getOutput();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("exitCode", result.getExitCode());
        meta.put("durationMs", result.getDurationMs());
        meta.put("truncated", result.isOutputTruncated());
        meta.put("timedOut", result.isTimedOut());
        meta.put("aborted", result.isAborted());
        meta.put("riskLevel", risk.name());

        if (result.isTimedOut()) {
            return new ToolResult("timed out after " + timeout + "s", outputStr, meta, true);
        }

        if (result.isAborted()) {
            return new ToolResult("aborted", outputStr, meta, true);
        }

        if (result.getExitCode() != 0) {
            return new ToolResult("exit " + result.getExitCode(), outputStr, meta, true);
        }

        return ToolResult.success("exit 0", outputStr, meta);
    }

    // --- Command classification ---

    enum CommandRisk {
        READONLY(PERM_READONLY, "read-only"),
        WRITE(PERM_WRITE, "write"),
        DESTRUCTIVE(PERM_DESTRUCTIVE, "destructive");

        final String permissionKey;
        final String label;

        CommandRisk(String permissionKey, String label) {
            this.permissionKey = permissionKey;
            this.label = label;
        }
    }

    /**
     * Parse a command string and classify its risk level.
     * Handles pipes and && chains — the highest risk segment wins.
     */
    static CommandRisk classifyCommand(String command) {
        // Split on pipes and logical operators to check each segment
        String[] segments = command.split("[|;&]+");
        CommandRisk highest = CommandRisk.READONLY;

        for (String segment : segments) {
            CommandRisk segRisk = classifySegment(segment.trim());
            if (segRisk.ordinal() > highest.ordinal()) {
                highest = segRisk;
            }
            if (highest == CommandRisk.DESTRUCTIVE) {
                return highest; // can't get higher
            }
        }

        // Also check full command against destructive patterns
        for (Pattern p : DESTRUCTIVE_PATTERNS) {
            if (p.matcher(command).find()) {
                return CommandRisk.DESTRUCTIVE;
            }
        }

        return highest;
    }

    private static CommandRisk classifySegment(String segment) {
        if (segment.isEmpty()) return CommandRisk.READONLY;

        // Strip leading env vars (FOO=bar cmd) and sudo
        String cleaned = segment.replaceAll("^(\\w+=\\S+\\s+)*", "");
        cleaned = cleaned.replaceAll("^sudo\\s+", "");

        // Extract the base command (first token)
        String[] tokens = cleaned.split("\\s+", 2);
        String baseCmd = tokens[0];
        // Strip path prefix (e.g., /usr/bin/ls -> ls)
        if (baseCmd.contains("/")) {
            baseCmd = baseCmd.substring(baseCmd.lastIndexOf('/') + 1);
        }

        String rest = tokens.length > 1 ? tokens[1] : "";

        // Special handling for git
        if ("git".equals(baseCmd)) {
            return classifyGitCommand(rest);
        }

        // Special handling for rm (may be read or destructive depending on flags)
        if ("rm".equals(baseCmd)) {
            return CommandRisk.DESTRUCTIVE;
        }

        // Check destructive commands
        if (DESTRUCTIVE_COMMANDS.contains(baseCmd)) {
            return CommandRisk.DESTRUCTIVE;
        }

        // Check read-only commands
        if (READONLY_COMMANDS.contains(baseCmd)) {
            return CommandRisk.READONLY;
        }

        // Check write commands
        if (WRITE_COMMANDS.contains(baseCmd)) {
            return CommandRisk.WRITE;
        }

        // Unknown commands default to write (will prompt)
        return CommandRisk.WRITE;
    }

    private static CommandRisk classifyGitCommand(String args) {
        if (args.isEmpty()) return CommandRisk.READONLY;

        String trimmed = args.trim();

        // Check destructive git patterns first
        for (String destructive : GIT_DESTRUCTIVE_SUBCOMMANDS) {
            if (trimmed.startsWith(destructive)) {
                return CommandRisk.DESTRUCTIVE;
            }
        }

        // Check read-only git subcommands
        String subcommand = trimmed.split("\\s+", 2)[0];
        if (GIT_READONLY_SUBCOMMANDS.contains(subcommand)) {
            return CommandRisk.READONLY;
        }

        // All other git commands (add, commit, checkout, merge, etc.) are write
        return CommandRisk.WRITE;
    }
}
