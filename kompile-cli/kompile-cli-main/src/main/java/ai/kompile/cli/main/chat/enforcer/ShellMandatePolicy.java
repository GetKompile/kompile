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
 * dedicated kompile file/search/memory tools.
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
 *   <li><b>File-content search</b> — {@code grep}/egrep/fgrep/rg/ag/ack reading a file or
 *       directory. Must use the {@code grep} tool.</li>
 *   <li><b>Direct file reads</b> — {@code cat}/head/tail/less/more/tac on a file. Must use
 *       the {@code read} tool.</li>
 *   <li><b>Directory listings</b> — {@code ls}. Must use the {@code list} tool.</li>
 *   <li><b>File discovery</b> — {@code find}/fd/locate. Must use the {@code glob} tool.</li>
 * </ul>
 *
 * <p>Filesystem administration (rm/rmdir, moves, copies, links, directory creation, and
 * modes/ownership) has no equivalent dedicated operation and proceeds to risk/permission
 * and judge review. Passing this mandate is not authorization to execute.</p>
 *
 * <h3>What stays allowed (deliberate, to protect legitimate work)</h3>
 * <ul>
     *   <li><b>Filtering a piped stream</b> — {@code mvn test | grep ERROR},
     *       {@code ps aux | awk '{print $2}'}. The stream source
     *       (a build, a process list, git) is not a project file, so nothing was searched or
     *       read via shell. A banned reader reading a FILE and piping onward
     *       ({@code cat build.log | grep error}) is still a violation — it is semantically
     *       {@code grep error build.log}. The exception is stream slicing: {@code head}/{@code tail}
     *       as pipeline filters are banned too — page results with {@code fetch_result},
     *       {@code process action=output} + {@code tail_lines}, or native flags ({@code git log -5}).</li>
 *   <li><b>Stdin sources</b> — {@code <} redirects, herestrings/heredocs, {@code -} stdin
 *       placeholders.</li>
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
            Map.entry("sed", "read/edit"), Map.entry("awk", "read/edit"), Map.entry("gawk", "read/edit"));

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

    /** Argument keys (checked in order) that carry the shell command text. */
    private static final List<String> COMMAND_FIELDS =
            List.of("command", "cmd", "script", "shell_command", "bash_command");
    private static final ObjectMapper JSON = JsonUtils.standardMapper();

    /** Command substitutions analyzed in addition to top-level pipeline segments. */
    private static final Pattern SUBST_PAREN = Pattern.compile("\\$\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
    private static final Pattern SUBST_BACKTICK = Pattern.compile("`([^`]*)`");

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern PERL_IN_PLACE_FLAG = Pattern.compile("-[A-Za-z]*i[A-Za-z.]*");
    private static final Pattern DURATION = Pattern.compile("[0-9]+[a-zA-Z]*");

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
     * Evaluate serialized JSON tool arguments (the {@code evaluateToolCall} shape).
     * Extracts the {@code command} field; a non-JSON payload is treated as the command text.
     *
     * @return a BLOCK decision, or {@code null} when the call is compliant / out of scope.
     */
    public static EnforcerToolCallDecision evaluateFromSerializedArgs(String toolName, String toolInput) {
        if (!isShellTool(toolName)) {
            return null;
        }
        return evaluateCommand(toolName, extractCommandFromJson(toolInput));
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
            // filters (grep/awk over a pipe) remain allowed.
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
                if (basename(token.text).equals(head) && !token.quotedStart) {
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
     * The tokenizer never stores an opening quote and keeps the closing one, so stripping
     * a trailing quote character is enough to recover the raw script text.
     */
    private static List<String> collectNestedShellBodies(List<Token> tokens) {
        List<String> bodies = new ArrayList<>();
        String head = null;
        boolean expectScript = false;
        for (Token token : tokens) {
            if (head == null) {
                if (!token.quotedStart && NESTED_SHELL_HEADS.contains(basename(token.text))) {
                    head = basename(token.text);
                }
                continue;
            }
            if (expectScript) {
                if (token.quotedStart) {
                    String body = token.text;
                    if (body.endsWith("'") || body.endsWith("\"")) {
                        body = body.substring(0, body.length() - 1);
                    }
                    if (body.startsWith("'") || body.startsWith("\"")) {
                        body = body.substring(1);
                    }
                    bodies.add(body);
                }
                expectScript = false;
                head = null;
                continue;
            }
            if ((token.text.equals("-c") || token.text.equals("-lc") || token.text.equals("-cl"))) {
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
                if (basename(token.text).equals(head) && !token.quotedStart) {
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
        return head == null ? null : basename(head);
    }

    private static String stripPrefixes(List<Token> tokens) {
        boolean skipDuration = false;
        for (Token token : tokens) {
            String text = token.text;
            if (skipDuration) {
                skipDuration = false;
                if (DURATION.matcher(text).matches()) {
                    continue;
                }
                return text;
            }
            if (!token.quotedStart && ENV_ASSIGNMENT.matcher(text).matches()) {
                continue;
            }
            if (COMMAND_PREFIXES.contains(text)) {
                skipDuration = "timeout".equals(text);
                continue;
            }
            return text;
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
     * Split a command into pipeline segments on unquoted {@code |}, {@code ;}, {@code &&},
     * {@code ||}, and newlines. Command substitutions are kept intact inside their segment
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
            if (c == ';' || c == '\n') {
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
                current.append(c);
                if (c == '\'') inSingle = false;
                i++;
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < n) {
                    current.append(c).append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') inDouble = false;
                current.append(c);
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
                current.append(text.charAt(i + 1));
                hasContent = true;
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
            if (Character.isWhitespace(c)) {
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
        correction.append("- File content search → `grep` tool (never `grep`/`rg`/`ag` in bash)\n");
        correction.append("- Reading files → `read` tool (never `cat`/`head`/`tail` on files)\n");
        correction.append("- Editing files → `edit` tool (never `sed -i`/`perl -i`/`awk -i inplace`)\n");
        correction.append("- Creating/replacing files → `write`/`patch` (never shell redirects, `tee`, `cp`, `mv`, etc.)\n");
        correction.append("- Persistent memory → `memory` tool (`todowrite` for task state); never generic file tools\n");
        correction.append("- Listing directories → `list` tool; finding files by pattern → `glob` tool\n");
        correction.append("`bash`/`process` remain available for builds/tests/git/system commands and for\n");
        correction.append("filtering piped streams (e.g. `mvn test | grep ERROR`, `ps aux | grep java`).\n");

        correction.append("\n## How to Re-Comply\n");
        correction.append("1. Re-issue the operation with the dedicated tool named above.\n");
        correction.append("2. Do NOT retry the shell form or an equivalent shell workaround.\n");
        correction.append("3. NEVER wait with `sleep`: launch work with `process action=launch` and add\n");
        correction.append("   `action=monitor` (or rely on the default completion monitor) so the harness wakes you\n");
        correction.append("   when the process exits; poll `action=status`/`output`/`stream` between other work instead.\n");
        return new EnforcerToolCallDecision(
                EnforcerToolCallDecision.Action.BLOCK, reason, list, correction.toString(), null);
    }
}
