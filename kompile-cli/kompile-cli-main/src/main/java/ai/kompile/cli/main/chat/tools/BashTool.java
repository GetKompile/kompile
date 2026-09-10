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
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy;
import ai.kompile.cli.main.chat.render.ProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Execute shell commands via bash with command-level permission classification.
 *
 * Commands are parsed and classified into risk tiers that map to permission keys
 * in the existing {@link ai.kompile.cli.main.chat.permission.PermissionService}:
 *
 * <ul>
 *   <li>{@code bash.readonly} — read-only commands (ls, cat, git status, etc.) — default ALLOW</li>
 *   <li>{@code bash.write} — permitted system commands that modify state (mvn, npm, git commit, etc.)</li>
 *   <li>{@code bash.destructive} — dangerous system commands (for example git push --force)</li>
 * </ul>
 *
 * <p>Shell content reads/writes with dedicated equivalents are rejected before permission
 * evaluation. Filesystem administration is risk-classified rather than categorically banned;
 * managed memory must still be changed through the memory tool.</p>
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

    /** Git subcommands whose built-in operation is unconditionally read-only. */
    private static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "describe", "shortlog",
            "blame", "ls-files", "ls-tree", "rev-parse", "rev-list"
    );

    /** Common Git-global options that preserve a subcommand's risk classification. */
    private static final Set<String> GIT_READONLY_GLOBAL_OPTIONS = Set.of(
            "--no-pager", "--paginate", "-p", "--no-replace-objects", "--bare"
    );

    /** Downstream commands that only format the stdout of a read-only Git invocation. */
    private static final Set<String> GIT_READONLY_OUTPUT_FILTERS = Set.of(
            "cat", "head", "tail", "less", "more", "wc", "grep", "egrep", "fgrep",
            "sort", "uniq", "cut", "jq"
    );

    private static final Set<String> GIT_BRANCH_MUTATION_OPTIONS = Set.of(
            "-d", "-D", "--delete", "-m", "-M", "--move", "-c", "-C", "--copy",
            "-f", "--force", "--set-upstream-to", "-u", "--unset-upstream",
            "--edit-description", "--track", "--no-track", "--recurse-submodules"
    );
    private static final Set<String> GIT_TAG_MUTATION_OPTIONS = Set.of(
            "-d", "--delete", "-a", "--annotate", "-s", "--sign", "-u",
            "--local-user", "-f", "--force"
    );
    private static final Set<String> GIT_CONFIG_MUTATION_OPTIONS = Set.of(
            "--add", "--unset", "--unset-all", "--replace-all", "--rename-section",
            "--remove-section", "--edit", "-e"
    );

    /** Option-aware patterns: match complete tokens regardless of option order. */
    private static final List<Pattern> GIT_DESTRUCTIVE_PATTERNS = List.of(
            gitOptionPattern("push", "--force(?:-with-lease|-if-includes)?(?:=[^ ]*)?|-f|-[A-Za-z]*f[A-Za-z]*|[+][^ ]+"),
            gitOptionPattern("reset", "--hard"),
            gitOptionPattern("clean", "--force|-[A-Za-z]*f[A-Za-z]*"),
            gitOptionPattern("branch", "-[A-Za-z]*D[A-Za-z]*"),
            Pattern.compile("^branch(?=.*[ ](?:--delete|-[A-Za-z]*d[A-Za-z]*)(?:[ ]|$))"
                    + "(?=.*[ ](?:--force|-[A-Za-z]*f[A-Za-z]*)(?:[ ]|$)).*$")
    );

    private static Pattern gitOptionPattern(String subcommand, String option) {
        return Pattern.compile("^" + subcommand + "(?:[ ]+[^ ]+)*[ ]+(?:" + option + ")(?:[ ]+.*)?$");
    }

    /** Top-level commands that are inherently destructive. */
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
            "rmdir", "unlink", "shred", "dd", "mkfs", "fdisk",
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
                "Commands are classified by risk level and checked against the permission policy. " +
                "Shell file reads, writes, redirects, and mutation commands are rejected when a " +
                "dedicated tool exists. Filesystem administration (rm, mv, mkdir, chmod) is subject to " +
                "risk permissions and judge policy, not categorically banned. Use memory for persistent memory mutations.";
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
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.DESTRUCTIVE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String command = params.path("command").asText("");
        int timeout = params.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
        String desc = params.path("description").asText("");

        if (command.isEmpty()) {
            return ToolResult.error("command is required");
        }

        EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateCommand(id(), command);
        if (mandate != null) {
            return ToolResult.error(mandate.getCorrectionPrompt());
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

        Consumer<String> liveOutput = context.getOutputConsumer();
        AtomicBoolean outputStreamed = new AtomicBoolean(false);

        // Execute via ProcessManager. Interactive chat installs a live output
        // consumer; MCP/headless callers still receive the same canonical result.
        ProcessManager.ProcessResult result = ProcessManager.executeInterruptibly(
                command, workDir, timeout * 1000, context::isAborted,
                liveOutput == null ? null : line -> {
                    outputStreamed.set(true);
                    liveOutput.accept(line);
                });

        String outputStr = result.getOutput();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("exitCode", result.getExitCode());
        meta.put("durationMs", result.getDurationMs());
        meta.put("truncated", result.isOutputTruncated());
        meta.put("timedOut", result.isTimedOut());
        meta.put("aborted", result.isAborted());
        meta.put("riskLevel", risk.name());
        meta.put(ToolResult.OUTPUT_STREAMED_METADATA, outputStreamed.get());

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

    /**
     * Reuse the command parser at higher-level policy gates without duplicating a
     * second, inevitably divergent shell-risk list.
     */
    public static String commandPermissionKey(String command) {
        return classifyCommand(command == null ? "" : command).permissionKey;
    }

    public static boolean isReadOnlyCommand(String command) {
        return classifyCommand(command == null ? "" : command) == CommandRisk.READONLY;
    }

    /**
     * Whether every Git invocation in a shell command is a recognized read-only inspection.
     * Non-Git pipeline filters are allowed only when the complete command remains read-only.
     */
    public static boolean isReadOnlyGitCommand(String command) {
        if (command == null || command.isBlank() || command.contains("GIT_")
                || command.contains("\n") || command.contains("\r")
                || command.contains("$(") || command.contains("`")
                || command.contains("<(") || command.contains(">(")
                || command.replace("&&", "").contains("&")
                || classifyCommand(command) != CommandRisk.READONLY) {
            return false;
        }
        boolean sawGit = false;
        for (String chain : command.split("&&|\\|\\||;")) {
            if (chain.isBlank()) return false;
            boolean chainSawGit = false;
            String[] pipeline = chain.split("\\|");
            for (int index = 0; index < pipeline.length; index++) {
                String segment = pipeline[index].trim();
                String gitArgs = gitArguments(segment);
                if (gitArgs != null) {
                    if (classifyGitCommand(gitArgs) != CommandRisk.READONLY) return false;
                    sawGit = true;
                    chainSawGit = true;
                } else if (index == 0 || !chainSawGit || !isReadOnlyGitOutputFilter(segment)) {
                    return false;
                }
            }
        }
        return sawGit;
    }

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
        String[] segments = command.split("[|;&\\r\\n]+");
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

        String trimmed = stripReadOnlyGitGlobalOptions(args.trim());
        if (trimmed == null) return CommandRisk.WRITE;
        if (trimmed.isEmpty()) return CommandRisk.READONLY;

        // Check destructive git patterns first
        for (Pattern destructive : GIT_DESTRUCTIVE_PATTERNS) {
            if (destructive.matcher(trimmed).matches()) {
                return CommandRisk.DESTRUCTIVE;
            }
        }

        String[] commandParts = trimmed.split("\\s+", 2);
        String subcommand = commandParts[0];
        String rest = commandParts.length > 1 ? commandParts[1] : "";
        if (hasGitOutputSideEffect(rest)) return CommandRisk.WRITE;

        // Check unconditionally read-only Git subcommands.
        if (GIT_READONLY_SUBCOMMANDS.contains(subcommand)) {
            return CommandRisk.READONLY;
        }

        // These Git commands mix query and mutation forms. Only certify narrow,
        // unmistakable list/query forms; unknown forms retain write classification.
        String firstArg = rest.isBlank() ? "" : rest.split("\\s+", 2)[0];
        return switch (subcommand) {
            case "branch" -> !containsGitOption(rest, GIT_BRANCH_MUTATION_OPTIONS)
                    && (rest.isBlank() || Set.of(
                    "-l", "--list", "--show-current", "-a", "--all",
                    "-r", "--remotes", "-v", "-vv").contains(firstArg))
                    ? CommandRisk.READONLY : CommandRisk.WRITE;
            case "tag" -> !containsGitOption(rest, GIT_TAG_MUTATION_OPTIONS)
                    && (rest.isBlank() || Set.of("-l", "--list").contains(firstArg))
                    ? CommandRisk.READONLY : CommandRisk.WRITE;
            case "remote" -> {
                String action = firstNonOption(rest);
                yield action == null || "get-url".equals(action)
                        ? CommandRisk.READONLY : CommandRisk.WRITE;
            }
            case "stash" -> Set.of("list", "show").contains(firstArg)
                    ? CommandRisk.READONLY : CommandRisk.WRITE;
            case "config" -> !containsGitOption(rest, GIT_CONFIG_MUTATION_OPTIONS)
                    && Set.of(
                    "--get", "--get-all", "--get-regexp", "--get-urlmatch",
                    "--list", "-l").contains(firstArg)
                    ? CommandRisk.READONLY : CommandRisk.WRITE;
            case "reflog" -> rest.isBlank() || "show".equals(firstArg)
                    ? CommandRisk.READONLY : CommandRisk.WRITE;
            default -> CommandRisk.WRITE;
        };
    }

    private static String stripReadOnlyGitGlobalOptions(String args) {
        List<String> tokens = new ArrayList<>(Arrays.asList(args.split("\\s+")));
        int index = 0;
        while (index < tokens.size()) {
            String option = tokens.get(index);
            if (GIT_READONLY_GLOBAL_OPTIONS.contains(option)) {
                index++;
            } else if (Set.of("-C", "--git-dir", "--work-tree").contains(option)) {
                if (index + 1 >= tokens.size()) return null;
                index += 2;
            } else if (option.startsWith("--git-dir=") || option.startsWith("--work-tree=")) {
                index++;
            } else {
                break;
            }
        }
        return String.join(" ", tokens.subList(index, tokens.size()));
    }

    private static boolean hasGitOutputSideEffect(String args) {
        for (String token : args.split("\\s+")) {
            if ("--output".equals(token) || token.startsWith("--output=")
                    || "--ext-diff".equals(token) || "--textconv".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGitOption(String args, Set<String> options) {
        if (args == null || args.isBlank()) return false;
        for (String token : args.split("\\s+")) {
            for (String option : options) {
                if (token.equals(option) || token.startsWith(option + "=")) return true;
            }
        }
        return false;
    }

    private static String firstNonOption(String args) {
        if (args == null || args.isBlank()) return null;
        for (String token : args.split("\\s+")) {
            if (!token.startsWith("-")) return token;
        }
        return null;
    }

    private static boolean isReadOnlyGitOutputFilter(String segment) {
        if (segment == null || segment.isBlank()) return false;
        String cleaned = segment.replaceAll("^(\\w+=\\S+\\s+)*", "");
        String base = cleaned.split("\\s+", 2)[0];
        if (base.contains("/")) base = base.substring(base.lastIndexOf('/') + 1);
        return GIT_READONLY_OUTPUT_FILTERS.contains(base);
    }

    private static String gitArguments(String segment) {
        if (segment.isEmpty()) return null;
        String cleaned = segment.replaceAll("^(\\w+=\\S+\\s+)*", "")
                .replaceFirst("^sudo\\s+", "");
        String[] parts = cleaned.split("\\s+", 2);
        String base = parts[0];
        if (base.contains("/")) base = base.substring(base.lastIndexOf('/') + 1);
        return "git".equals(base) ? (parts.length > 1 ? parts[1] : "") : null;
    }
}
