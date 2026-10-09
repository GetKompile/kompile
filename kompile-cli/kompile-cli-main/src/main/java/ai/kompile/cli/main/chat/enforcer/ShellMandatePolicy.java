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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic harness mandate that stops shell-execution tools from bypassing the
 * dedicated kompile file/search/memory tools, and native file tools from writing managed memory.
 *
 * <p>The tool-mandate table in AGENTS.md bans using shell equivalents
 * ({@code sed -i}, {@code grep}, {@code cat}, {@code find}, {@code ls}, ...) for operations
 * a dedicated tool performs. Keyword rules only catch what a user enumerated by hand, and
 * the LLM judge is probabilistic and fails open, so shell-smuggled {@code sed} / {@code grep}
 * and output redirects previously slipped through harness-level tool-call validation. This policy closes that
 * gap deterministically — no LLM, no latency, no fail-open.</p>
 *
 * <h3>What is blocked</h3>
 * <ul>
 *   <li><b>In-place rewrites</b> — {@code sed -i}, {@code perl -i}/-pi, {@code awk -i inplace}
 *       overwrite files and must go through the {@code edit} tool. Always blocked.</li>
 *   <li><b>Shell content writes</b> — output redirects, {@code tee}, {@code touch},
 *       {@code truncate}, and {@code patch}. Content changes use {@code write}/{@code edit}/{@code patch};
 *       managed memory must use the {@code memory} tool.</li>
 *   <li><b>Managed-memory shell access</b> — a shell command may not name Kompile or
 *       provider memory paths, including through an inline Python/Node script.</li>
 *   <li><b>Shell search/filtering</b> — {@code grep}/egrep/fgrep/rg/ag/ack on files,
 *       directories, piped streams, or stdin. Must use the {@code grep} tool.</li>
 *   <li><b>Direct file reads</b> — {@code cat}/head/tail/less/more/tac on a file. Must use
 *       the {@code read} tool.</li>
 *   <li><b>Shell sed</b> — blocked on files, piped streams, and stdin alike. Search/filter
 *       with the {@code grep} tool; rewrite files with {@code edit}.</li>
 *   <li><b>Directory listings</b> — {@code ls}. Must use the {@code list} tool.</li>
 *   <li><b>File discovery</b> — {@code find}/fd/locate. Must use the {@code glob} tool.</li>
 *   <li><b>Shell loops</b> — {@code for}, {@code while}, {@code until}, and {@code select},
 *       including nested shell scripts. Use host-monitored processes or dedicated job-status tools.</li>
 * </ul>
 *
 * <p>Filesystem administration (rm/rmdir, moves, copies, links, directory creation, and
 * modes/ownership) has no equivalent dedicated operation and proceeds to risk/permission
 * and judge review. Passing this mandate is not authorization to execute.</p>
 *
 * <h3>What stays allowed (deliberate, to protect legitimate work)</h3>
 * <ul>
 *   <li><b>Other piped filters</b> — {@code ps aux | awk '{print $2}'}. Shell search commands,
 *       {@code sed}, and {@code head}/{@code tail} are banned even as pipeline filters.
 *       Capture command output with {@code process}, then use the {@code grep} tool on its
 *       output file; page results with {@code fetch_result} or {@code process action=output}.</li>
 *   <li><b>Stdin sources</b> — {@code <} redirects, herestrings/heredocs, {@code -} stdin
 *       placeholders, except for {@code sed} and shell search commands.</li>
 *   <li><b>Null-device redirects</b> — {@code 2>/dev/null} and equivalent forms suppress
 *       process output without creating or modifying an artifact.</li>
 *   <li><b>Non-shell tools</b> — the dedicated {@code grep}/{@code read}/{@code edit}/...
 *       tools are the compliant path and are never evaluated here.</li>
 * </ul>
 *
 * <p>Like {@link JudgeToolPolicy}, this is a hard deterministic layer. It is wired into
 * {@link EnforcerToolCallGuard#evaluate} (MCP stdio + daemon sessions),
 * {@code EnforcerJudge.evaluateToolCall} (inline judge lane), and
 * {@link KeywordEnforcerEvaluator#evaluateToolCall} (inline enforcer lane) so every
 * harness tool-call validation path enforces it.</p>
 */
public final class ShellMandatePolicy {

    private ShellMandatePolicy() {}

    /** Canonical (un-namespaced, lowercase) tools that execute shell commands. */
    private static final Set<String> SHELL_TOOLS =
            Set.of("bash", "sh", "shell", "zsh", "terminal", "exec", "process");

    /** Claude-native mutations bypass ToolContext's managed-memory guard. */
    private static final Set<String> FILE_MUTATION_TOOLS = Set.of(
            "write", "edit", "multiedit", "notebookedit", "edit_batch", "edit_patch", "patch");
    private static final List<String> MUTATION_PATH_FIELDS = List.of(
            "file_path", "path", "file", "notebook_path");
    private static final Set<String> PROVIDER_MEMORY_DIRS = Set.of(
            ".claude", ".codex", ".gemini", ".qwen", ".opencode");

    /** Prefix words that do not change the executed command; skipped when finding the head. */
    private static final Set<String> COMMAND_PREFIXES =
            Set.of("sudo", "nohup", "env", "command", "exec", "time", "nice", "stdbuf", "timeout", "setsid");

    /**
     * Mandate readers that are blocked when they take file/directory operands.
     * Maps the bare command to the dedicated tool that must be used instead.
     */
    private static final Map<String, String> FILE_READERS = Map.ofEntries(
            Map.entry("cat", "read"), Map.entry("head", "read"), Map.entry("tail", "read"),
            Map.entry("less", "read"), Map.entry("more", "read"), Map.entry("tac", "read"),
            Map.entry("ls", "list"),
            Map.entry("find", "glob"), Map.entry("fd", "glob"), Map.entry("locate", "glob"),
            Map.entry("grep", "grep"), Map.entry("egrep", "grep"), Map.entry("fgrep", "grep"),
            Map.entry("rg", "grep"), Map.entry("ag", "grep"), Map.entry("ack", "grep"),
            Map.entry("awk", "read/edit"), Map.entry("gawk", "read/edit"));

    /** Content operations with actual dedicated-tool equivalents. Filesystem administration
     * (directory removal, moves, modes, links) instead passes through command risk and user policy. */
    private static final Map<String, String> FILE_WRITERS = Map.ofEntries(
            Map.entry("tee", "write"), Map.entry("touch", "write"),
            Map.entry("truncate", "write"), Map.entry("patch", "patch"));

    /**
     * Timing/delay commands that are banned outright: a model that wants to wait must use
     * the {@code process} tool's completion {@code monitor} (or {@code status}/{@code output}/{@code stream}),
     * not burn a turn sleeping. "watch" is excluded because it has a legitimate
     * file-observation sense the mandate would mislabel, and it is not part of the sleep loop pattern.
     */
    private static final Map<String, String> SLEEP_COMMANDS = Map.of(
            "sleep", "process monitor",
            "usleep", "process monitor",
            "snooze", "process monitor",
            "at", "process monitor");

    /** Shell reserved words, not arbitrary occurrences in arguments or quoted text. */
    private static final Set<String> LOOP_HEADS = Set.of("for", "while", "until", "select");
    private static final Set<String> CONTROL_PREFIXES = Set.of("if", "then", "elif", "else", "do", "!");

    /** One-or-more GNU sleep duration terms: 5, 0.5, 30s, 5ms, 1h30m. */
    private static final Pattern SLEEP_SUFFIX =
            Pattern.compile("^(?:[0-9]+(?:\\.[0-9]+)?(?:ms|us|s|m|h|d)?)+$");

    private static final Pattern AT_TIME = Pattern.compile("^(?:[01]?[0-9]|2[0-3]):[0-5][0-9]$|^(?:[01]?[0-9]|2[0-3])(?:am|pm)$");

    /**
     * Stream slicers banned even as pipeline filters: output paging has dedicated harness paths
     * ({@code fetch_result} offset/limit for cached results, {@code process action=output} +
     * {@code tail_lines} or {@code action=stream} for command output, native flags such as
     * {@code git log -5}). {@code tac}/{@code less}/{@code more} stay file-readers only.
     */
    private static final Set<String> STREAM_SLICERS = Set.of("head", "tail");

    /** Search commands never bypass the dedicated tool via pipes or stdin. */
    private static final Set<String> SEARCH_COMMANDS = Set.of("grep", "egrep", "fgrep", "rg", "ag", "ack");

    /** Argument keys (checked in order) that carry the shell command text. */
    private static final List<String> COMMAND_FIELDS =
            List.of("command", "cmd", "script", "shell_command", "bash_command");
    private static final ObjectMapper JSON = JsonUtils.standardMapper();

    /** Command substitutions analyzed in addition to top-level pipeline segments. */
    private static final Pattern SUBST_PAREN = Pattern.compile("\\$\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
    private static final Pattern SUBST_BACKTICK = Pattern.compile("`([^`]*)`");

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern PERL_IN_PLACE_FLAG = Pattern.compile("-[A-Za-z]*i[A-Za-z.]*");
    private static final Pattern DURATION = Pattern.compile("[0-9]+(?:\\.[0-9]+)?[a-zA-Z]*");

    /** Wrapper options whose next word is an option value, not the command. */
    private static final Map<String, Set<String>> WRAPPER_VALUE_OPTIONS = Map.of(
            "sudo", Set.of("-u", "--user", "-g", "--group", "-h", "--host", "-p", "--prompt", "-C", "--close-from", "-D", "--chdir"),
            "env", Set.of("-u", "--unset", "-C", "--chdir"),
            "timeout", Set.of("-s", "--signal", "-k", "--kill-after"),
            "nice", Set.of("-n", "--adjustment"),
            "stdbuf", Set.of("-i", "--input", "-o", "--output", "-e", "--error"),
            "time", Set.of("-f", "--format", "-o", "--output"));

    /** A pipeline segment plus whether stdin is fed by a preceding pipe. */
    private static final class Segment {
        final String text;
        final boolean pipedFromPrevious;

        Segment(String text, boolean pipedFromPrevious) {
            this.text = text;
            this.pipedFromPrevious = pipedFromPrevious;
        }
    }

    /** Token plus whether its text began inside a quote (redirect detection needs this). */
    private static final class Token {
        final String text;
        final boolean quotedStart;

        Token(String text, boolean quotedStart) {
            this.text = text;
            this.quotedStart = quotedStart;
        }
    }

    // ── Public entry points ──────────────────────────────────────────────

    /**
     * Evaluate raw (already extracted) command text for a tool call.
     *
     * @return a BLOCK decision, or {@code null} when the call is compliant / out of scope.
     */
    public static EnforcerToolCallDecision evaluateCommand(String toolName, String commandText) {
        if (!isShellTool(toolName)) {
            return null;
        }
        if (commandText == null || commandText.isBlank()) {
            return null;
        }

        Set<String> violations = new LinkedHashSet<>();
        if (containsShellLoop(commandText)) {
            violations.add("Shell loops (`for`/`while`/`until`/`select`) are banned — launch work with "
                    + "`process action=launch` and use its host-enforced completion monitor or a dedicated job-status tool; "
                    + "do not sleep or busy-poll in a shell script");
            return buildDecision(violations);
        }
        for (Segment segment : splitPipeline(commandText)) {
            analyzeSegment(segment, violations);
        }
        for (String substitution : collectSubstitutions(commandText)) {
            for (Segment segment : splitPipeline(substitution)) {
                analyzeSegment(segment, violations);
            }
        }

        if (violations.isEmpty()) {
            return null;
        }
        return buildDecision(violations);
    }

    /**
     * Evaluate serialized tool arguments, including native file mutations of managed memory.
     * Shell arguments extract the command field; a non-JSON shell payload remains command text.
     *
     * @return a BLOCK decision, or {@code null} when the call is compliant / out of scope.
     */
    public static EnforcerToolCallDecision evaluateFromSerializedArgs(String toolName, String toolInput) {
        return evaluateFromSerializedArgs(toolName, toolInput, Path.of(System.getProperty("user.dir")));
    }

    /** Provider hooks supply their event cwd so relative paths and symlink aliases are checked correctly. */
    public static EnforcerToolCallDecision evaluateFromSerializedArgs(
            String toolName, String toolInput, Path workingDirectory) {
        if (FILE_MUTATION_TOOLS.contains(JudgeToolPolicy.canonicalToolName(toolName))) {
            try {
                JsonNode args = JSON.readTree(toolInput == null ? "{}" : toolInput);
                if (args == null || !args.isObject()) {
                    return memoryMutationDecision("Cannot inspect file mutation arguments");
                }
                String target = managedMemoryTarget(args, workingDirectory);
                if (target != null) {
                    return memoryMutationDecision("Direct file mutation of managed memory is blocked: " + target);
                }
            } catch (JsonProcessingException | InvalidPathException e) {
                return memoryMutationDecision("Cannot inspect file mutation arguments");
            }
        }
        if (!isShellTool(toolName)) {
            return null;
        }
        return evaluateCommand(toolName, extractCommandFromJson(toolInput));
    }

    private static EnforcerToolCallDecision memoryMutationDecision(String reason) {
        String correction = reason + ". Save memories with Kompile's `memory` MCP tool"
                + " (`mcp__kompile__memory`, action=save/write/append), not Write/Edit or direct .claude files.";
        return new EnforcerToolCallDecision(EnforcerToolCallDecision.Action.BLOCK,
                reason, List.of(reason), correction, null);
    }

    /** Inspect only target fields, never document contents or replacement strings. */
    private static String managedMemoryTarget(JsonNode args, Path workingDirectory) {
        for (String field : MUTATION_PATH_FIELDS) {
            JsonNode value = args.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) continue;
            String target = value.asText();
            String expanded = target.startsWith("~/")
                    ? System.getProperty("user.home") + target.substring(1) : target;
            Path path = workingDirectory.resolve(expanded).toAbsolutePath().normalize();
            if (isManagedMemoryTarget(path) || isManagedMemoryTarget(realMutationTarget(path))) return target;
        }
        for (String field : List.of("edits", "patches")) {
            JsonNode entries = args.path(field);
            if (!entries.isArray()) continue;
            for (JsonNode entry : entries) {
                String target = managedMemoryTarget(entry, workingDirectory);
                if (target != null) return target;
            }
        }
        return null;
    }

    private static boolean isManagedMemoryTarget(Path path) {
        String previous = null;
        boolean insideProvider = false;
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (("memory".equals(name) && (".kompile".equals(previous) || insideProvider))
                    || ("memory.md".equals(name) && insideProvider)) return true;
            if (PROVIDER_MEMORY_DIRS.contains(name)) insideProvider = true;
            previous = name;
        }
        return false;
    }

    /** Resolve the nearest existing ancestor, including when a new file is under a memory symlink. */
    private static Path realMutationTarget(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return path;
        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (IOException e) {
            return path;
        }
    }

    /** Shared deterministic loop check for the mandate and enforced workflow gates. */
    public static boolean containsShellLoop(String command) {
        if (command == null || command.isBlank()) return false;
        for (Segment segment : splitPipeline(command)) {
            String text = segment.text.replace("\\\n", "").trim();
            while (text.startsWith("(") || text.startsWith("{")) {
                text = text.substring(1).trim();
            }
            List<Token> tokens = tokenize(text);
            for (Token token : tokens) {
                if (token.quotedStart) break;
                if (LOOP_HEADS.contains(token.text)) return true;
                if (!CONTROL_PREFIXES.contains(token.text)) break;
            }
            String head = headCommand(tokens);
            if (head != null && NESTED_SHELL_HEADS.contains(head)) {
                for (String body : collectNestedShellBodies(tokens)) {
                    if (containsShellLoop(body)) return true;
                }
            }
        }
        for (String substitution : collectSubstitutions(command)) {
            if (containsShellLoop(substitution)) return true;
        }
        return false;
    }

    public static boolean isShellTool(String toolName) {
        return SHELL_TOOLS.contains(JudgeToolPolicy.canonicalToolName(toolName));
    }

    /** Pull the shell command out of serialized tool arguments without regex backtracking. */
    static String extractCommandFromJson(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        String trimmed = toolInput.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed; // plain-text argument form
        }
        try {
            JsonNode root = JSON.readTree(trimmed);
            if (root == null || !root.isObject()) {
                return null;
            }
            for (String field : COMMAND_FIELDS) {
                JsonNode command = root.get(field);
                if (command != null && command.isTextual()) {
                    return command.textValue();
                }
            }
        } catch (JsonProcessingException ignored) {
            // Unknown or malformed argument shapes fail open; the LLM judge still reviews them.
        }
        return null;
    }

    // ── Analysis ─────────────────────────────────────────────────────────

    private static void analyzeSegment(Segment segment, Set<String> violations) {
        String text = segment.text.trim();
        // Subshell / brace-group decorations do not change the head command.
        while (text.startsWith("(") || text.startsWith("{")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return;
        }

        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return;
        }

        if (referencesManagedMemory(text)) {
            violations.add("Managed memory must not be accessed through shell commands — use the kompile `memory` tool"
                    + " (`todowrite` for transcript-session task state)"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }

        if (hasFileOutputRedirection(text)) {
            violations.add("Shell output redirection writes files directly — use the kompile `write`/`edit` tool"
                    + " for ordinary files or the `memory` tool for managed memory"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }

        if (detectInPlaceRewrite(tokens)) {
            violations.add("In-place file rewrite via shell (sed -i / perl -i / awk -i inplace) is banned"
                    + " — use the kompile `edit` tool instead"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }

        String head = headCommand(tokens);
        // Dedicated-tool commands are banned before any pipe/stdin exceptions.
        if ("cat".equals(head)) {
            violations.add("Shell `cat` is banned, including pipelines and stdin — use the kompile"
                    + " `read` tool, or `process action=output`/`stream` for captured command output"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }
        if ("sed".equals(head)) {
            violations.add("Shell `sed` is banned, including pipeline and stdin filtering — use the kompile"
                    + " `grep` tool for searching/filtering, `read` for line ranges, or `edit` for file rewrites"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }
        if (head != null && SEARCH_COMMANDS.contains(head)) {
            violations.add("Shell `" + head + "` is banned, including pipeline and stdin filtering — use the kompile"
                    + " `grep` tool instead; search captured command output with its path argument"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }
        if (head != null && SLEEP_COMMANDS.containsKey(head) && isSleepInvocation(head, tokens)) {
            violations.add("Shell `" + head + "` just burns a turn waiting — use the `process` tool instead: "
                    + "launch with `process action=launch`, then `process action=monitor` (wake me on exit), "
                    + "or poll `process action=status`/`output` — never `sleep`"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }
        if (head != null && NESTED_SHELL_HEADS.contains(head)) {
            for (String embedded : collectNestedShellBodies(tokens)) {
                for (Segment nested : splitPipeline(embedded)) {
                    analyzeSegment(nested, violations);
                }
            }
        }
        if (head != null && FILE_WRITERS.containsKey(head)) {
            violations.add("Shell `" + head + "` mutates files directly — use the kompile `"
                    + FILE_WRITERS.get(head) + "` tool instead"
                    + (text.length() > 120 ? "" : ": `" + text + "`"));
            return;
        }
        if (head == null || !FILE_READERS.containsKey(head)) {
            return;
        }
        if (segment.pipedFromPrevious || hasStdinSource(tokens)) {
            // Slicing a stream (or a redirected file) with head/tail is the exact misuse the
            // dedicated result-paging and process-output paths exist for — block it; other
            // filters (awk over a pipe) remain allowed.
            if (STREAM_SLICERS.contains(head) && !consumesInlinedText(tokens)) {
                violations.add("Shell `" + head + "` output slicing is banned — page large results with"
                        + " `fetch_result` (offset/limit), read command output via `process action=output`"
                        + " + `tail_lines` (or `action=stream`), limit sources natively (`git log -5`),"
                        + " and search with the `grep` tool"
                        + (text.length() > 120 ? "" : ": `" + text + "`"));
            }
            return; // filtering a stream, not reading a file
        }
        violations.add("Shell `" + head + "` reads files/directories directly — use the kompile `"
                + FILE_READERS.get(head) + "` tool instead of bash " + head
                + (text.length() > 120 ? "" : ": `" + text + "`"));
    }

    /** True when the segment invokes sed/perl/awk with an in-place (file-overwriting) flag. */
    private static boolean detectInPlaceRewrite(List<Token> tokens) {
        String head = stripPrefixes(tokens);
        if (head == null) {
            return false;
        }
        List<String> rest = new ArrayList<>();
        boolean seenHead = false;
        for (Token token : tokens) {
            if (!seenHead) {
                if (basename(token.text).equals(head)) {
                    seenHead = true;
                }
                continue;
            }
            rest.add(token.text);
        }
        if (!seenHead) {
            return false;
        }
        switch (head) {
            case "sed":
                // GNU sed: -i[SUFFIX] (e.g. -i, -i.bak, -ie) and --in-place[=SUFFIX].
                // No other sed short option starts with 'i', so any -i* is in-place.
                for (String arg : rest) {
                    if (arg.startsWith("-i") || arg.equals("--in-place") || arg.startsWith("--in-place=")) {
                        return true;
                    }
                }
                return false;
            case "perl":
                for (String arg : rest) {
                    if (PERL_IN_PLACE_FLAG.matcher(arg).matches()) {
                        return true;
                    }
                }
                return false;
            case "awk":
            case "gawk": {
                String joined = String.join(" ", rest);
                return joined.contains("-i inplace") || joined.contains("--include=inplace")
                        || joined.contains("--include inplace");
            }
            default:
                return false;
        }
    }

    /**
     * Interpreter heads whose {@code -c} script argument executes as a shell: a mandate-relevant
     * command hidden in the script must be analyzed as if written inline.
     */
    private static final Set<String> NESTED_SHELL_HEADS = Set.of("bash", "sh", "zsh");

    /**
     * Extract quoted script bodies passed to a nested shell ({@code bash -c '<script>'}).
     * Tokens carry shell-decoded words, including concatenated quoted/unquoted fragments.
     */
    private static List<String> collectNestedShellBodies(List<Token> tokens) {
        List<String> bodies = new ArrayList<>();
        String head = null;
        boolean expectScript = false;
        for (Token token : tokens) {
            if (head == null) {
                if (NESTED_SHELL_HEADS.contains(basename(token.text))) {
                    head = basename(token.text);
                }
                continue;
            }
            if (expectScript) {
                bodies.add(token.text);
                expectScript = false;
                head = null;
                continue;
            }
            if (token.text.startsWith("-") && !token.text.startsWith("--") && token.text.contains("c")) {
                expectScript = true;
                continue;
            }
            if (!token.text.startsWith("-")) {
                head = null; // recognizable `shell -c '<script>'` form ended
            }
        }
        return bodies;
    }

    /**
     * True when a {@code sleep}-family head really is a delay invocation: a duration argument
     * for sleep/usleep/snooze, or a time spec for {@code at}. A bare head with no duration
     * (e.g. a different binary on PATH) stays allowed — the mandate blocks the pattern, not names.
     */
    private static boolean isSleepInvocation(String head, List<Token> tokens) {
        boolean seenHead = false;
        for (Token token : tokens) {
            if (!seenHead) {
                if (basename(token.text).equals(head)) {
                    seenHead = true;
                }
                continue;
            }
            String arg = token.text;
            if (arg.startsWith("-")) {
                continue; // flags like -f, -q, -M for `at`
            }
            if (head.equals("at")) {
                if (AT_TIME.matcher(arg).matches() || arg.startsWith("now")) {
                    return true;
                }
                continue;
            }
            if (SLEEP_SUFFIX.matcher(arg).matches()) {
                return true;
            }
            return false;
        }
        return false;
    }

    /** Resolve the head command of a token list, skipping env assignments and wrappers. */
    private static String headCommand(List<Token> tokens) {
        String head = stripPrefixes(tokens);
        if (head == null) return null;
        return basename(head);
    }

    private static String stripPrefixes(List<Token> tokens) {
        for (int i = 0; i < tokens.size();) {
            Token token = tokens.get(i);
            String text = token.text;
            if (!token.quotedStart && (ENV_ASSIGNMENT.matcher(text).matches()
                    || CONTROL_PREFIXES.contains(text))) {
                i++;
                continue;
            }
            String wrapper = basename(text);
            if (!COMMAND_PREFIXES.contains(wrapper)) return text;
            i++;
            while (i < tokens.size() && tokens.get(i).text.startsWith("-")) {
                String option = tokens.get(i++).text;
                if ("command".equals(wrapper) && ("-v".equals(option) || "-V".equals(option))) {
                    return text; // command lookup, not execution
                }
                if ("--".equals(option)) break;
                if (WRAPPER_VALUE_OPTIONS.getOrDefault(wrapper, Set.of()).contains(option) && i < tokens.size()) {
                    i++;
                }
            }
            if ("timeout".equals(wrapper) && i < tokens.size()
                    && DURATION.matcher(tokens.get(i).text).matches()) {
                i++;
            }
        }
        return null;
    }

    private static String basename(String command) {
        int slash = command.lastIndexOf('/');
        return (slash >= 0 ? command.substring(slash + 1) : command).toLowerCase(Locale.ROOT);
    }

    /** True when the slicer's input is text the model wrote inline (herestring/heredoc or `-`),
     * not a stream or file being fished through. `<<`-prefixed tokens cover both herestring
     * ({@code <<<}) and heredoc ({@code <<'EOF'}) markers. */
    private static boolean consumesInlinedText(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.quotedStart) {
                continue;
            }
            if (token.text.startsWith("<<") || token.text.equals("-")) {
                return true;
            }
        }
        return false;
    }

    /** True when the reader's input comes from a redirect, herestring, heredoc, or `-`. */
    private static boolean hasStdinSource(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.quotedStart) {
                continue; // "<div" is a pattern, not a redirect
            }
            String text = token.text;
            if (text.startsWith("<") || text.equals("-")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect an unquoted shell output redirect. Descriptor duplication such as
     * {@code 2>&1}, descriptor closing such as {@code 3>&-}, and redirects to the
     * null device do not create or mutate an artifact and therefore remain allowed.
     */
    private static boolean hasFileOutputRedirection(String text) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (singleQuote) {
                if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                continue;
            }
            if (c != '>') continue;

            int target = i + 1;
            if (target < text.length() && (text.charAt(target) == '>' || text.charAt(target) == '|')) {
                target++;
            }
            while (target < text.length() && Character.isWhitespace(text.charAt(target))) {
                target++;
            }
            if (target < text.length() && text.charAt(target) == '&') {
                int descriptor = target + 1;
                if (descriptor < text.length()
                        && (Character.isDigit(text.charAt(descriptor)) || text.charAt(descriptor) == '-')) {
                    continue;
                }
            }
            if (isNullDeviceTarget(text, target)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isNullDeviceTarget(String text, int target) {
        if (target >= text.length()) {
            return false;
        }

        char quote = text.charAt(target);
        if (quote == '\'' || quote == '"') {
            int closingQuote = text.indexOf(quote, target + 1);
            return closingQuote >= 0
                    && text.substring(target + 1, closingQuote).equals("/dev/null")
                    && isRedirectionBoundary(text, closingQuote + 1);
        }

        String nullDevice = "/dev/null";
        return text.regionMatches(target, nullDevice, 0, nullDevice.length())
                && isRedirectionBoundary(text, target + nullDevice.length());
    }

    private static boolean isRedirectionBoundary(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        char next = text.charAt(index);
        return Character.isWhitespace(next) || next == ';' || next == '|'
                || next == '&' || next == ')' || next == '}';
    }

    private static boolean referencesManagedMemory(String text) {
        String normalized = text.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains(".kompile/memory")) {
            return true;
        }
        for (String provider : List.of(".claude", ".codex", ".gemini", ".qwen", ".opencode")) {
            int providerIndex = normalized.indexOf(provider + "/");
            while (providerIndex >= 0) {
                int memoryIndex = normalized.indexOf("/memory", providerIndex + provider.length());
                if (memoryIndex >= 0) {
                    int end = memoryIndex + "/memory".length();
                    if (end == normalized.length()
                            || normalized.charAt(end) == '/'
                            || !Character.isLetterOrDigit(normalized.charAt(end))) {
                        return true;
                    }
                }
                providerIndex = normalized.indexOf(provider + "/", providerIndex + provider.length());
            }
        }
        return false;
    }

    // ── Shell text plumbing ──────────────────────────────────────────────

    /**
     * Split a command into pipeline segments on unquoted {@code |}, {@code ;}, {@code &},
     * {@code &&}, {@code ||}, and newlines. Command substitutions are kept intact inside their segment
     * (and analyzed separately), so pipes inside {@code $(...)} do not create segments.
     */
    private static List<Segment> splitPipeline(String command) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean pipedFromPrevious = false;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        int i = 0;
        int n = command.length();

        while (i < n) {
            char c = command.charAt(i);
            if (singleQuote) {
                current.append(c);
                if (c == '\'') singleQuote = false;
                i++;
                continue;
            }
            if (doubleQuote) {
                if (c == '\\' && i + 1 < n) {
                    current.append(c).append(command.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') doubleQuote = false;
                current.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
                current.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                current.append(c);
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                current.append(c).append(command.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && command.charAt(i + 1) == '(') {
                int close = matchParen(command, i + 1);
                current.append(command, i, close + 1);
                i = close + 1;
                continue;
            }
            if (c == '`') {
                int close = command.indexOf('`', i + 1);
                if (close < 0) {
                    current.append(c);
                    i++;
                    continue;
                }
                current.append(command, i, close + 1);
                i = close + 1;
                continue;
            }
            if (c == '|' && i + 1 < n && command.charAt(i + 1) == '|') {
                segments.add(new Segment(current.toString(), pipedFromPrevious));
                current.setLength(0);
                pipedFromPrevious = false;
                i += 2;
                continue;
            }
            if (c == '|') {
                // "|&" pipes stderr too; treat it like a normal pipe.
                boolean stderrPipe = i + 1 < n && command.charAt(i + 1) == '&';
                segments.add(new Segment(current.toString(), pipedFromPrevious));
                current.setLength(0);
                pipedFromPrevious = true;
                i += stderrPipe ? 2 : 1;
                continue;
            }
            if (c == '&' && i + 1 < n && command.charAt(i + 1) == '&') {
                segments.add(new Segment(current.toString(), pipedFromPrevious));
                current.setLength(0);
                pipedFromPrevious = false;
                i += 2;
                continue;
            }
            // A single '&' separates background commands, except in fd redirects (&> / >& / <&).
            if ((c == '&' && (i == 0 || (command.charAt(i - 1) != '>' && command.charAt(i - 1) != '<'))
                    && (i + 1 == n || command.charAt(i + 1) != '>')) || c == ';' || c == '\n') {
                segments.add(new Segment(current.toString(), pipedFromPrevious));
                current.setLength(0);
                pipedFromPrevious = false;
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        segments.add(new Segment(current.toString(), pipedFromPrevious));
        return segments;
    }

    private static int matchParen(String text, int openIndex) {
        int depth = 0;
        for (int j = openIndex; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return j;
            }
        }
        return text.length() - 1;
    }

    private static List<String> collectSubstitutions(String command) {
        List<String> substitutions = new ArrayList<>();
        Matcher paren = SUBST_PAREN.matcher(command);
        while (paren.find()) {
            substitutions.add(paren.group(1));
        }
        Matcher backtick = SUBST_BACKTICK.matcher(command);
        while (backtick.find()) {
            substitutions.add(backtick.group(1));
        }
        return substitutions;
    }

    /** Whitespace tokenizer that is aware of quotes, escapes, and substitutions. */
    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasContent = false;
        boolean quotedStart = false;
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
                i++;
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < n) {
                    // Shell line continuations contribute no characters to the token.
                    if (text.charAt(i + 1) != '\n') {
                        char escaped = text.charAt(i + 1);
                        if (escaped != '$' && escaped != '`' && escaped != '"' && escaped != '\\') current.append(c);
                        current.append(escaped);
                    }
                    i += 2;
                    continue;
                }
                if (c == '"') inDouble = false;
                else current.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                if (!hasContent) quotedStart = true;
                hasContent = true;
                i++;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                if (!hasContent) quotedStart = true;
                hasContent = true;
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                // Do not turn a continued line into a fake command head or split executable.
                if (text.charAt(i + 1) != '\n') {
                    current.append(text.charAt(i + 1));
                    hasContent = true;
                }
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && text.charAt(i + 1) == '(') {
                int close = matchParen(text, i + 1);
                current.append(text, i, close + 1);
                hasContent = true;
                i = close + 1;
                continue;
            }
            // Unquoted subshell delimiters are shell operators, not executable-name suffixes.
            if (Character.isWhitespace(c) || c == '(' || c == ')') {
                if (hasContent) {
                    tokens.add(new Token(current.toString(), quotedStart));
                    current.setLength(0);
                    hasContent = false;
                    quotedStart = false;
                }
                i++;
                continue;
            }
            current.append(c);
            hasContent = true;
            i++;
        }
        if (hasContent) {
            tokens.add(new Token(current.toString(), quotedStart));
        }
        return tokens;
    }

    // ── Decision ─────────────────────────────────────────────────────────

    private static EnforcerToolCallDecision buildDecision(Set<String> violations) {
        List<String> list = List.copyOf(violations);
        String reason = "Kompile tool mandate: shell commands must not replace dedicated file/search/memory tools";
        StringBuilder correction = new StringBuilder();
        correction.append("STOP. Your bash tool call was blocked by the kompile tool mandate.\n\n");

        correction.append("## Violations\n");
        for (String violation : list) {
            correction.append("- ").append(violation).append('\n');
        }

        correction.append("\n## The Rule\n");
        correction.append("Use the dedicated kompile MCP tools for file operations:\n");
        correction.append("- Search/filtering → `grep` tool (never shell `grep`/`egrep`/`fgrep`/`rg`/`ag`/`ack`, even in pipelines or on stdin)\n");
        correction.append("- `sed` search/filtering → `grep` tool; line ranges → `read` offset/limit"
                + " (never shell `sed`, even in pipelines or on stdin)\n");
        correction.append("- Reading files → `read` tool (never shell `cat`, including pipelines/stdin; never `head`/`tail` on files)\n");
        correction.append("- Editing files → `edit` tool (never `sed -i`/`perl -i`/`awk -i inplace`)\n");
        correction.append("- Creating/replacing files → `write`/`patch` (never shell redirects, `tee`, `cp`, `mv`, etc.)\n");
        correction.append("- Persistent memory → `memory` tool (`todowrite` for task state); never generic file tools\n");
        correction.append("- Listing directories → `list` tool; finding files by pattern → `glob` tool\n");
        correction.append("`bash`/`process` remain available for builds/tests/git/system commands.\n");
        correction.append("Capture command output with `process`, then use the `grep` tool on the output file;"
                + " never retry shell search/filter commands after `cd`, in a chain, or behind a wrapper.\n");

        correction.append("\n## How to Re-Comply\n");
        correction.append("1. Re-issue the operation with the dedicated tool named above.\n");
        correction.append("2. Do NOT retry the shell form or an equivalent shell workaround.\n");
        correction.append("3. NEVER wait with `sleep` or shell loops (`for`/`while`/`until`/`select`):\n");
        correction.append("   launch work with `process action=launch` and add\n");
        correction.append("   `action=monitor` (or rely on the default completion monitor) so the harness wakes you\n");
        correction.append("   when the process exits; poll `action=status`/`output`/`stream` between other work instead.\n");
        return new EnforcerToolCallDecision(
                EnforcerToolCallDecision.Action.BLOCK, reason, list, correction.toString(), null);
    }
}
