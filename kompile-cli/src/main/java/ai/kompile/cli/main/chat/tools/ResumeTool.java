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

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.SessionIndex;
import ai.kompile.cli.main.chat.format.ConversationExporter;
import ai.kompile.cli.main.chat.format.ConversationFormatter;
import ai.kompile.cli.main.chat.format.ConversationReader;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Comparator;

/**
 * Resume Tool - Multi-tab TUI for browsing, searching, migrating, and resuming conversations.
 * <p>
 * Provides an interactive interface to:
 * <ul>
 *   <li>Browse conversations grouped by agent/source in a tabbed interface</li>
 *   <li>Search conversations by keyword, agent, or date range</li>
 *   <li>View conversation transcripts and metadata</li>
 *   <li>Migrate conversations between formats and agents</li>
 *   <li>Resume conversations with a designated agent via passthrough mode</li>
 * </ul>
 * <p>
 * Usage: Launch from the chat TUI or directly via {@code kompile resume}
 */
public class ResumeTool implements CliTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    // ANSI color codes
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String MAGENTA = "\033[35m";
    private static final String WHITE = "\033[37m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";

    private Terminal terminal;
    private LineReader lineReader;
    private final TerminalRenderer terminalRenderer;
    private final AsciiRenderer ascii;
    private final ConversationImportTool importTool;
    private final ConversationFormatter formatter;
    private final ConversationReader reader;
    private final boolean mcpMode;

    // Current state
    private List<ConversationSummary> allConversations;
    private List<ConversationSummary> filteredConversations;
    private int currentTab = 0;
    private int currentPage = 0;
    private static final int PAGE_SIZE = 15;
    private String searchQuery = "";
    private String filterAgent = "";
    private String filterSource = "";

    // Sort state
    private String sortField = "date"; // date, title, agent
    private boolean sortAscending = false; // default: most recent first for date

    // Scope state: local-only (current directory) vs all projects
    private boolean localOnly = true;
    private final String currentWorkingDir = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().normalize().toString();

    private record LoadedConversation(List<ChatHistory.Turn> turns, String source, Path workingDirectory) {}

    /**
     * Constructor for MCP / non-interactive mode.
     * When {@code mcpMode} is true, NO terminal or line reader is created at all —
     * this avoids grabbing the system TTY, intercepting Ctrl+C signals, or changing
     * terminal attributes. Interactive browsing is disabled; search/resume/view/migrate
     * return structured data as text output. A real system terminal is only created
     * lazily if the 'browse' action is invoked.
     *
     * @param mcpMode true when running inside an MCP server (no interactive terminal needed)
     */
    public ResumeTool(boolean mcpMode) throws IOException {
        this.mcpMode = mcpMode;
        if (mcpMode) {
            // No terminal, no line reader — avoid all TTY/signal interference
            this.terminal = null;
            this.lineReader = null;
        } else {
            this.terminal = TerminalBuilder.builder().system(true).build();
            this.lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        }
        this.terminalRenderer = new TerminalRenderer(true);
        this.ascii = new AsciiRenderer(terminalRenderer);
        this.importTool = new ConversationImportTool();
        this.formatter = new ConversationFormatter();
        this.reader = new ConversationReader();
        this.allConversations = new ArrayList<>();
        this.filteredConversations = new ArrayList<>();
    }

    /**
     * Constructor initializes the Resume Tool with a full system terminal for interactive use.
     */
    public ResumeTool() throws IOException {
        this(false);
    }

    /**
     * Package-private constructor for testing with injected dependencies.
     */
    ResumeTool(Terminal terminal, LineReader lineReader, AsciiRenderer ascii,
               ConversationImportTool importTool, ConversationFormatter formatter,
               ConversationReader reader) {
        this.mcpMode = false;
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.ascii = ascii;
        this.terminalRenderer = null; // Not used in test constructor
        this.importTool = importTool;
        this.formatter = formatter;
        this.reader = reader;
        this.allConversations = new ArrayList<>();
        this.filteredConversations = new ArrayList<>();
    }

    @Override
    public String id() {
        return "resume";
    }

    @Override
    public String description() {
        return "Interactive multi-tab tool for browsing, searching, migrating, and resuming conversations. " +
                "Allows loading migrated conversations into designated agents via passthrough mode.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'browse' (interactive TUI), 'search', 'migrate', 'resume', 'view'");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query for filtering conversations");

        ObjectNode agent = props.putObject("agent");
        agent.put("type", "string");
        agent.put("description", "Filter by agent name (e.g., 'claude', 'codex', 'qwen')");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "Filter by source (e.g., 'kompile', 'claude-code', 'opencode')");

        ObjectNode sessionId = props.putObject("session_id");
        sessionId.put("type", "string");
        sessionId.put("description", "Session ID for view/migrate/resume actions");

        ObjectNode targetAgent = props.putObject("target_agent");
        targetAgent.put("type", "string");
        targetAgent.put("description", "Target agent for resume/migrate actions");

        ObjectNode outputFormat = props.putObject("output_format");
        outputFormat.put("type", "string");
        outputFormat.put("description", "Output format for migration: 'kompile', 'openai', 'anthropic', 'markdown', 'jsonl'");

        ObjectNode targetSessionId = props.putObject("target_session_id");
        targetSessionId.put("type", "string");
        targetSessionId.put("description", "UUID to use as the target session ID when resuming (instead of generating a new one)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return null; // No special permission needed
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        try {
            String action = params.has("action") ? params.get("action").asText() : "browse";

            switch (action.toLowerCase()) {
                case "browse":
                    return runInteractiveBrowser();
                case "search":
                    return runSearch(params);
                case "migrate":
                    return runMigrate(params);
                case "resume":
                    return runResume(params);
                case "view":
                    return runView(params);
                default:
                    return ToolResult.error("Unknown action: " + action);
            }
        } catch (Exception e) {
            return ToolResult.error("Resume tool error: " + e.getMessage());
        }
    }

    /**
     * Resolve a user input string to a session ID.
     * If the input is a number, treat it as a display number from the current page.
     * Otherwise, treat it as a raw session ID.
     */
    private String resolveSessionId(String input) {
        try {
            int displayNum = Integer.parseInt(input);
            int start = currentPage * PAGE_SIZE;
            int index = start + displayNum - 1; // 1-based to 0-based
            if (index >= 0 && index < filteredConversations.size()) {
                return filteredConversations.get(index).sessionId();
            }
            // Index out of range — return null to signal invalid selection
            return null;
        } catch (NumberFormatException e) {
            // Not a number, treat as raw session ID
        }
        return input;
    }

    /**
     * Resolve a session ID from MCP action parameters.
     * If the input is a number, treat it as a 1-based index into the filteredConversations
     * list from the most recent search. If the list is empty (no prior search), attempt
     * to load all conversations first so that numeric indices still work.
     * If the input is not a number, treat it as a raw session ID or UUID.
     * Returns null if the numeric index is out of range.
     */
    private String resolveSessionIdFromSearchResults(String input) {
        String trimmed = input.trim();

        // Ensure we have conversations loaded for partial matching
        if (!conversationsLoaded) {
            loadAllConversations();
            searchQuery = "";
            filterAgent = "";
            filterSource = "";
            applyFilters();
        } else if (filteredConversations == null || filteredConversations.isEmpty()) {
            // Conversations loaded but filtered to empty — reset filters
            searchQuery = "";
            filterAgent = "";
            filterSource = "";
            applyFilters();
        }

        // Try numeric index first (1-based)
        try {
            int index = Integer.parseInt(trimmed);
            int zeroBasedIndex = index - 1;
            if (zeroBasedIndex >= 0 && zeroBasedIndex < filteredConversations.size()) {
                return filteredConversations.get(zeroBasedIndex).sessionId();
            }
            // Number but out of range — fall through to partial match
            // (the number might be part of a session ID)
        } catch (NumberFormatException e) {
            // Not a number — continue to partial matching
        }

        // Partial string matching: check if input is a prefix or substring of any session ID
        String lower = trimmed.toLowerCase();

        // Exact match first
        for (ConversationSummary convo : filteredConversations) {
            if (convo.sessionId().equalsIgnoreCase(trimmed)) {
                return convo.sessionId();
            }
        }

        // Prefix match (e.g., first few chars of a UUID)
        ConversationSummary prefixMatch = null;
        int prefixCount = 0;
        for (ConversationSummary convo : filteredConversations) {
            if (convo.sessionId().toLowerCase().startsWith(lower)) {
                prefixMatch = convo;
                prefixCount++;
            }
        }
        if (prefixCount == 1) {
            return prefixMatch.sessionId();
        }

        // Substring match (e.g., partial UUID from the middle)
        ConversationSummary substringMatch = null;
        int substringCount = 0;
        for (ConversationSummary convo : filteredConversations) {
            if (convo.sessionId().toLowerCase().contains(lower)) {
                substringMatch = convo;
                substringCount++;
            }
        }
        if (substringCount == 1) {
            return substringMatch.sessionId();
        }

        // Also check allConversations in case filtered list is narrower
        for (ConversationSummary convo : allConversations) {
            if (convo.sessionId().equalsIgnoreCase(trimmed)) {
                return convo.sessionId();
            }
        }
        prefixMatch = null;
        prefixCount = 0;
        for (ConversationSummary convo : allConversations) {
            if (convo.sessionId().toLowerCase().startsWith(lower)) {
                prefixMatch = convo;
                prefixCount++;
            }
        }
        if (prefixCount == 1) {
            return prefixMatch.sessionId();
        }

        // No match found — return the raw input so loadConversation can try
        // external sources (claude-code, codex, etc.) with it directly
        return trimmed;
    }

    /**
     * Run the interactive multi-tab browser.
     * In MCP mode, upgrades to a real system terminal for interactive use.
     */
    public ToolResult runInteractiveBrowser() {
        try {
            // In MCP mode, we started with no terminal — create one for interactive use
            if (terminal == null || lineReader == null) {
                this.terminal = TerminalBuilder.builder().system(true).build();
                this.lineReader = LineReaderBuilder.builder().terminal(terminal).build();
            }

            terminal.writer().print("\033[2J");
            terminal.writer().flush();
            // Small delay to let terminal settle after creation
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            loadAllConversations();
            // Apply initial filters - this also sets the first tab's agent filter
            applyFilters();
            setFilterForCurrentTab();
            refreshView(); // re-apply with the tab filter set and load visible titles

            while (true) {
                renderMainView();
                String input;
                try {
                    input = lineReader.readLine("Command (h for help, q to quit): ");
                } catch (UserInterruptException e) {
                    // Ctrl+C pressed - treat as quit
                    input = "q";
                } catch (EndOfFileException e) {
                    // EOF (Ctrl+D) - treat as quit
                    input = "q";
                } catch (Exception e) {
                    // Terminal error, ask again
                    try { Thread.sleep(500); } catch (InterruptedException ie) {}
                    continue;
                }

                if (input == null || input.trim().isEmpty()) {
                    // readLine returned null or empty - terminal likely in inconsistent state
                    // Ask again instead of exiting
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    continue;
                }

                // Handle multi-line piped input (each line is a separate command)
                String[] lines = input.split("\\r?\\n");
                boolean shouldQuit = false;
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.equalsIgnoreCase("q") || line.equalsIgnoreCase("quit")
                            || line.equalsIgnoreCase("back") || line.equalsIgnoreCase("b")
                            || line.equalsIgnoreCase("menu") || line.equalsIgnoreCase("setup")) {
                        shouldQuit = true;
                        break;
                    }
                    processCommand(line);
                }
                if (shouldQuit) {
                    // Clean up terminal state before exiting
                    terminal.writer().print("\033[2J");
                    terminal.writer().flush();
                    terminal.writer().print("\033[0m");
                    terminal.writer().print("\033[?25h");
                    terminal.writer().println();
                    terminal.writer().flush();
                    terminal.close();
                    return ToolResult.success("Quit resume tool");
                }
            }

            // Clean up terminal state before exiting
            terminal.writer().print("\033[2J");
            terminal.writer().flush();
            terminal.writer().print("\033[0m");
            terminal.writer().print("\033[?25h");
            terminal.writer().println();
            terminal.writer().flush();
            terminal.close();

            return ToolResult.success("Resume tool exited.");
        } catch (Exception e) {
            // Ensure terminal is cleaned up on exception to prevent resource leak
            try {
                terminal.writer().print("\033[2J");
                terminal.writer().flush();
                terminal.writer().print("\033[0m");
                terminal.writer().print("\033[?25h");
                terminal.writer().println();
                terminal.writer().flush();
                terminal.close();
            } catch (Exception closeError) {
                // Ignore cleanup errors
            }
            return ToolResult.error("Interactive browser error: " + e.getMessage());
        }
    }

    /**
     * Process a single command from the user.
     */
    private void processCommand(String input) {
        try {
            String[] parts = input.trim().split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String rest = parts.length > 1 ? parts[1] : "";

            // Quick switch: just a number = switch agent tab
            if (command.matches("\\d+")) {
                switchTab(command);
                return;
            }

            switch (command) {
                case "h":
                case "help":
                    showHelp();
                    break;
                case "tab":
                case "t":
                    if (!rest.isEmpty()) {
                        switchTab(rest.trim());
                    }
                    break;
                case "search":
                case "s":
                    if (!rest.isEmpty()) {
                        searchQuery = rest;
                        currentPage = 0;
                        refreshView();
                    }
                    break;
                case "filter":
                case "f":
                    if (!rest.isEmpty()) {
                        handleFilter(rest.trim());
                    }
                    break;
                case "view":
                case "v":
                    if (!rest.isEmpty()) {
                        String sid = resolveSessionId(rest.trim());
                        if (sid == null) {
                            terminal.writer().println(RED + "Invalid selection — number out of range." + RESET);
                            terminal.writer().flush();
                        } else {
                            viewConversation(sid);
                        }
                    }
                    break;
                case "migrate":
                case "m":
                    if (!rest.isEmpty()) {
                        String sid = resolveSessionId(rest.trim());
                        if (sid == null) {
                            terminal.writer().println(RED + "Invalid selection — number out of range." + RESET);
                            terminal.writer().flush();
                        } else {
                            migrateConversation(sid);
                        }
                    }
                    break;
                case "resume":
                case "r":
                    if (!rest.isEmpty()) {
                        // Support: resume <id> [uuid]
                        String[] resumeParts = rest.trim().split("\\s+", 2);
                        String sid = resolveSessionId(resumeParts[0]);
                        String targetUuid = resumeParts.length > 1 ? resumeParts[1].trim() : null;
                        if (sid == null) {
                            terminal.writer().println(RED + "Invalid selection — number out of range." + RESET);
                            terminal.writer().flush();
                        } else {
                            resumeConversation(sid, filterAgent, targetUuid);
                        }
                    }
                    break;
                case "next":
                case "n":
                    nextPage();
                    break;
                case "prev":
                case "p":
                    prevPage();
                    break;
                case "page":
                    if (!rest.isEmpty()) {
                        goToPage(rest.trim());
                    }
                    break;
                case "sort":
                    if (!rest.isEmpty()) {
                        handleSort(rest.trim());
                    }
                    break;
                case "all":
                case "global":
                    // Load conversations from ALL projects, not just current directory
                    localOnly = false;
                    currentPage = 0;
                    loadAllConversations();
                    setFilterForCurrentTab();
                    refreshView();
                    terminal.writer().println(GREEN + "  Loaded all conversations across all projects." + RESET);
                    terminal.writer().flush();
                    break;
                case "local":
                    // Switch back to local-only mode
                    localOnly = true;
                    currentPage = 0;
                    loadAllConversations();
                    setFilterForCurrentTab();
                    refreshView();
                    terminal.writer().println(GREEN + "  Showing conversations for current directory only." + RESET);
                    terminal.writer().flush();
                    break;
                case "clear":
                case "c":
                    clearFilters();
                    break;
                case "back":
                case "b":
                case "menu":
                case "setup":
                    // Handled as quit in the main loop
                    break;
                case "":
                    break;
                default:
                    terminal.writer().println(RED + "Unknown command: " + command + RESET);
                    terminal.writer().flush();
                    break;
            }
        } catch (UserInterruptException e) {
            // User pressed Ctrl+C - treat as quit signal, handled by caller
        } catch (Exception e) {
            // Don't let command errors crash the interactive browser
            try {
                terminal.writer().println(RED + "Command error: " + e.getMessage() + RESET);
                terminal.writer().flush();
            } catch (Exception ignored) {
                // Terminal may be in bad state after subprocess exit
            }
        }
    }

    /**
     * Load conversations from kompile and external sources.
     * When localOnly is true, only loads sessions associated with the current working directory.
     */
    /**
     * Set of external session IDs that have been harvested into kompile transcripts.
     * These are excluded from external conversation listings to prevent duplicates.
     */
    private final Set<String> harvestedExternalIds = new HashSet<>();
    private final Map<String, Path> sessionFilePaths = new HashMap<>();
    private boolean conversationsLoaded = false;

    private void loadAllConversations() {
        allConversations.clear();
        harvestedExternalIds.clear();
        sessionFilePaths.clear();

        // Load kompile sessions (always loaded - these are local passthrough sessions)
        try {
            List<ChatHistory.ConversationSummary> kompileSessions = ChatHistory.listConversations();
            for (ChatHistory.ConversationSummary session : kompileSessions) {
                // Collect harvested external session IDs for deduplication
                harvestedExternalIds.addAll(session.harvestedSourceIds());
                allConversations.add(new ConversationSummary(
                        session.sessionId(),
                        formatTitle(session.title()),
                        session.started(),
                        normalizeAgentName(session.agent()),
                        "kompile",
                        formatDate(session.lastModified()),
                        session.lastModified()
                ));
            }
        } catch (Exception e) {
            if (terminal != null) {
                terminal.writer().println(YELLOW + "Warning: Could not load kompile sessions: " + e.getMessage() + RESET);
            } else {
                System.err.println("Warning: Could not load kompile sessions: " + e.getMessage());
            }
        }

        // Load external agent conversations (skipping harvested duplicates)
        loadExternalConversations("claude-code", "claude");
        loadExternalConversations("opencode", "opencode");
        loadExternalConversations("codex", "codex");
        loadExternalConversations("qwen", "qwen");
        loadExternalConversations("gemini", "gemini");

        // Fallback dedup: for sessions that predate the [harvested:] marker,
        // remove external sessions that overlap in time with a kompile passthrough session.
        // A kompile passthrough session and its source JSONL will have very close modification times.
        deduplicateByTimeProximity();

        // Sort by last modified (most recent first)
        allConversations.sort(Comparator.comparing(ConversationSummary::lastModifiedTimestamp).reversed());
        conversationsLoaded = true;
    }

    /**
     * Remove external agent sessions that are likely duplicates of kompile passthrough sessions.
     * A kompile passthrough session harvests the agent's JSONL, so both appear with very close
     * modification times and matching agents. This catches pre-[harvested:] sessions.
     */
    private void deduplicateByTimeProximity() {
        // Collect kompile passthrough sessions with their timestamps
        List<ConversationSummary> kompileSessions = allConversations.stream()
                .filter(c -> "kompile".equals(c.source()) && c.sessionId().startsWith("passthrough-"))
                .collect(Collectors.toList());
        if (kompileSessions.isEmpty()) return;

        // For each external session, check if it's within 2 minutes of a kompile passthrough
        // session with a matching agent — if so, it's likely the same conversation
        Set<String> toRemove = new HashSet<>();
        for (ConversationSummary external : allConversations) {
            if ("kompile".equals(external.source())) continue;
            for (ConversationSummary kompile : kompileSessions) {
                if (!kompile.agent().equals(external.agent())) continue;
                long timeDiff = Math.abs(kompile.lastModifiedTimestamp() - external.lastModifiedTimestamp());
                if (timeDiff < 120_000) { // 2 minutes
                    toRemove.add(external.sessionId());
                    break;
                }
            }
        }
        if (!toRemove.isEmpty()) {
            allConversations.removeIf(c -> toRemove.contains(c.sessionId()) && !"kompile".equals(c.source()));
        }
    }

    /**
     * Format a title - extract meaningful preview from content, truncate long titles.
     * Returns empty string if no usable title can be derived (callers use sessionId as fallback).
     */
    private String formatTitle(String title) {
        if (title == null || title.isEmpty()) return "";
        // Clean up leading whitespace, markdown headers, etc.
        String cleaned = title.trim();
        if (cleaned.startsWith("# ")) {
            cleaned = cleaned.substring(2);
        }
        // Strip XML/HTML tags (e.g. <command-message>loop</command-message> → loop)
        if (cleaned.contains("<")) {
            cleaned = cleaned.replaceAll("<[^>]+>", "").trim();
        }
        // If stripping tags left nothing, return empty
        if (cleaned.isEmpty()) return "";
        // For enforcer messages, extract the actual user prompt from after "## User Prompt"
        if (cleaned.startsWith("Enforcer-Controlled Task") || cleaned.startsWith("Enforcer Correction")) {
            int userPromptIdx = cleaned.indexOf("## User Prompt");
            if (userPromptIdx >= 0) {
                String afterHeader = cleaned.substring(userPromptIdx + "## User Prompt".length()).trim();
                // Skip blank lines
                if (!afterHeader.isEmpty()) {
                    // Take the first non-empty line
                    String[] lines = afterHeader.split("\\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("##") &&
                                !trimmed.startsWith("Produce the response now")) {
                            cleaned = trimmed;
                            break;
                        }
                    }
                }
            }
        }
        // Strip system-prompt prefixes that aren't useful as titles
        if (cleaned.startsWith("[ENFORCER RULES]") || cleaned.startsWith("You are ")) {
            // Take a shorter snippet for system prompts
            int end = Math.min(cleaned.length(), 50);
            int sentenceEnd = cleaned.indexOf('.', 0);
            if (sentenceEnd > 0 && sentenceEnd < end) end = sentenceEnd;
            cleaned = cleaned.substring(0, end).trim();
        }
        // Take first line only for title
        int newlineIdx = cleaned.indexOf('\n');
        if (newlineIdx > 0) {
            cleaned = cleaned.substring(0, newlineIdx).trim();
        }
        // Truncate to 60 chars
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 57) + "...";
        }
        return cleaned;
    }

    /**
     * Format a timestamp to a human-readable date.
     */
    private String formatDate(long timestampMs) {
        try {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestampMs);
            java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MMM dd HH:mm");
            return zdt.format(fmt);
        } catch (Exception e) {
            return String.valueOf(timestampMs);
        }
    }

    /**
     * Normalize agent names to avoid duplicates (e.g., "claude" and "claude code" → "claude").
     */
    private String normalizeAgentName(String agent) {
        if (agent == null) return "unknown";
        String lower = agent.toLowerCase().trim();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("codex")) return "codex";
        if (lower.contains("gemini")) return "gemini";
        if (lower.contains("qwen")) return "qwen";
        if (lower.contains("opencode") || lower.contains("open-code")) return "opencode";
        return lower;
    }

    /**
     * Load conversations from an external agent source.
     * When localOnly is true, only loads sessions associated with the current working directory.
     * Does NOT read file contents for titles - uses placeholders instead for fast loading.
     */
    private void loadExternalConversations(String source, String agentName) {
        try {
            String homeDir = System.getProperty("user.home");

            switch (source) {
                case "claude-code" -> {
                    Path claudeDir = Paths.get(homeDir, ".claude", "projects");
                    if (Files.isDirectory(claudeDir)) {
                        List<Path> projectDirsToScan;
                        if (localOnly) {
                            // Only scan project dirs that match the current working directory
                            projectDirsToScan = findMatchingClaudeProjectDirs(claudeDir, currentWorkingDir);
                        } else {
                            try (java.util.stream.Stream<Path> dirs = Files.list(claudeDir)) {
                                projectDirsToScan = dirs.filter(Files::isDirectory).collect(Collectors.toList());
                            }
                        }
                        for (Path projectDir : projectDirsToScan) {
                            try (java.util.stream.Stream<Path> sessionFiles = Files.list(projectDir)) {
                                sessionFiles.filter(p -> p.toString().endsWith(".jsonl"))
                                    .forEach(p -> {
                                        String id = p.getFileName().toString().replace(".jsonl", "");
                                        // Skip sessions already harvested into a kompile transcript
                                        if (harvestedExternalIds.contains(id)) return;
                                        try {
                                            long lastMod = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                                            sessionFilePaths.put(id, p);
                                            allConversations.add(new ConversationSummary(
                                                    id,
                                                    "", // empty = lazy load on demand
                                                    String.valueOf(lastMod),
                                                    agentName.toLowerCase(),
                                                    "claude-code",
                                                    formatDate(lastMod),
                                                    lastMod
                                            ));
                                        } catch (IOException ignored) {}
                                    });
                            } catch (IOException ignored) {}
                        }
                    }
                }
                case "codex" -> {
                    Path codexDir = Paths.get(homeDir, ".codex");
                    if (Files.isDirectory(codexDir)) {
                        try (java.util.stream.Stream<Path> files = Files.walk(codexDir)) {
                            files.filter(p -> p.getFileName().toString().startsWith("rollout-") &&
                                            p.toString().endsWith(".jsonl"))
                                .forEach(p -> {
                                    // When localOnly, check cwd from session_meta
                                    if (localOnly && !codexSessionMatchesCwd(p, currentWorkingDir)) {
                                        return;
                                    }
                                    String id = readCodexSessionId(p);
                                    if (id == null || id.isBlank()) {
                                        return;
                                    }
                                    // Skip sessions already harvested into a kompile transcript
                                    String fileId = p.getFileName().toString().replace(".jsonl", "");
                                    if (harvestedExternalIds.contains(id) || harvestedExternalIds.contains(fileId)) return;
                                    try {
                                        long lastMod = Files.getLastModifiedTime(p).toMillis();
                                        sessionFilePaths.put(id, p);
                                        allConversations.add(new ConversationSummary(
                                                id,
                                                "",
                                                String.valueOf(lastMod),
                                                agentName.toLowerCase(),
                                                "codex",
                                                formatDate(lastMod),
                                                lastMod
                                        ));
                                    } catch (IOException ignored) {}
                                });
                        } catch (IOException ignored) {}
                    }
                }
                case "qwen" -> {
                    Path qwenDir = Paths.get(homeDir, ".qwen", "projects");
                    if (Files.isDirectory(qwenDir)) {
                        if (localOnly) {
                            // Qwen uses same path encoding as Claude Code
                            List<Path> matchingDirs = findMatchingClaudeProjectDirs(qwenDir, currentWorkingDir);
                            for (Path projDir : matchingDirs) {
                                Path chatsDir = projDir.resolve("chats");
                                if (Files.isDirectory(chatsDir)) {
                                    loadQwenChatsDir(chatsDir, agentName);
                                }
                            }
                        } else {
                            try (java.util.stream.Stream<Path> projectDirs = Files.walk(qwenDir, 4)) {
                                projectDirs.filter(p -> p.toString().endsWith("chats") && Files.isDirectory(p))
                                    .forEach(chatsDir -> loadQwenChatsDir(chatsDir, agentName));
                            } catch (IOException ignored) {}
                        }
                    }
                }
                case "opencode" -> {
                    Path sessionDir = Paths.get(homeDir, ".local", "share", "opencode", "storage", "session");
                    if (Files.isDirectory(sessionDir)) {
                        try (java.util.stream.Stream<Path> projectDirs = Files.list(sessionDir)) {
                            projectDirs.filter(Files::isDirectory).forEach(projDir -> {
                                try (java.util.stream.Stream<Path> sessionFiles = Files.list(projDir)) {
                                    sessionFiles.filter(p -> p.toString().endsWith(".json"))
                                        .forEach(p -> {
                                            // When localOnly, check directory field from JSON
                                            if (localOnly && !openCodeSessionMatchesCwd(p, currentWorkingDir)) {
                                                return;
                                            }
                                            String id = p.getFileName().toString().replace(".json", "");
                                            // Skip sessions already harvested into a kompile transcript
                                            if (harvestedExternalIds.contains(id)) return;
                                            try {
                                                long lastMod = Files.getLastModifiedTime(p).toMillis();
                                                sessionFilePaths.put(id, p);
                                                allConversations.add(new ConversationSummary(
                                                        id,
                                                        "",
                                                        String.valueOf(lastMod),
                                                        agentName.toLowerCase(),
                                                        "opencode",
                                                        formatDate(lastMod),
                                                        lastMod
                                                ));
                                            } catch (IOException ignored) {}
                                        });
                                } catch (IOException ignored) {}
                            });
                        }
                    }
                }
                case "gemini" -> {
                    Path geminiDir = Paths.get(homeDir, ".gemini");
                    if (Files.isDirectory(geminiDir)) {
                        Path tmpDir = geminiDir.resolve("tmp");
                        if (Files.isDirectory(tmpDir)) {
                            List<Path> projectDirsToScan;
                            if (localOnly) {
                                // Gemini uses SHA256(path) as project directory name
                                String hash = sha256(currentWorkingDir);
                                Path matchDir = tmpDir.resolve(hash);
                                projectDirsToScan = Files.isDirectory(matchDir)
                                        ? List.of(matchDir) : List.of();
                            } else {
                                try (java.util.stream.Stream<Path> dirs = Files.list(tmpDir)) {
                                    projectDirsToScan = dirs.filter(Files::isDirectory)
                                            .filter(pd -> !pd.getFileName().toString().equals("bin"))
                                            .collect(Collectors.toList());
                                }
                            }
                            for (Path projDir : projectDirsToScan) {
                                Path chatsDir = projDir.resolve("chats");
                                if (Files.isDirectory(chatsDir)) {
                                    try (java.util.stream.Stream<Path> sessionFiles = Files.list(chatsDir)) {
                                        sessionFiles.filter(p -> p.toString().endsWith(".json"))
                                            .forEach(p -> {
                                                String id = readGeminiSessionId(p);
                                                if (id == null || id.isBlank()) {
                                                    return;
                                                }
                                                // Skip sessions already harvested into a kompile transcript
                                                String fileId = p.getFileName().toString().replaceFirst("\\.(json|jsonl)$", "");
                                                if (harvestedExternalIds.contains(id) || harvestedExternalIds.contains(fileId)) return;
                                                try {
                                                    long lastMod = Files.getLastModifiedTime(p).toMillis();
                                                    sessionFilePaths.put(id, p);
                                                    allConversations.add(new ConversationSummary(
                                                            id,
                                                            "",
                                                            String.valueOf(lastMod),
                                                            agentName.toLowerCase(),
                                                            "gemini",
                                                            formatDate(lastMod),
                                                            lastMod
                                                    ));
                                                } catch (IOException ignored) {}
                                            });
                                    } catch (IOException ignored) {}
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently skip sources that fail
        }
    }

    /**
     * Lazy-load title from file only when needed (e.g., when viewing or resuming).
     * Uses cached file paths from enumeration for O(1) lookup instead of re-walking directories.
     * Returns the formatted title or falls back to the session ID.
     */
    private String loadTitleForConversation(String sessionId, String source) {
        try {
            Path filePath = sessionFilePaths.get(sessionId);
            if (filePath != null && Files.exists(filePath)) {
                return extractFirstLineFromFile(filePath, source);
            }
        } catch (Exception ignored) {}
        return ""; // empty = display code will fall back to sessionId
    }

    /**
     * Read the first user message from an external conversation file to use as a title.
     * Streams line-by-line to avoid loading entire files into memory (sessions can be 100MB+).
     * Stops scanning after MAX_SCAN_LINES since the first user message is always near the top.
     */
    private String extractFirstLineFromFile(Path filePath, String source) {
        final int MAX_SCAN_LINES = 100;
        try {
            // Gemini uses a single JSON object, not JSONL — stream-parse to find first user message
            if ("gemini".equals(source)) {
                com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(filePath.toFile());
                com.fasterxml.jackson.databind.JsonNode messages = root.path("messages");
                if (messages.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode msg : messages) {
                        String type = msg.path("type").asText("");
                        if ("user".equals(type)) {
                            String text = msg.path("content").asText("");
                            if (!text.isEmpty()) {
                                return formatTitle(text);
                            }
                        }
                    }
                }
                return "";
            }

            // All other agents use JSONL — stream line-by-line, stop after finding first user message
            try (java.io.BufferedReader reader = Files.newBufferedReader(filePath, java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                int linesRead = 0;
                while ((line = reader.readLine()) != null && linesRead++ < MAX_SCAN_LINES) {
                    if (line.isBlank()) continue;
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(line);
                        String extracted = extractUserText(node, source);
                        if (extracted != null) {
                            return formatTitle(extracted);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Extract user message text from a JSONL node based on the agent source format.
     * Returns the text if this node is a user message, null otherwise.
     */
    private String extractUserText(com.fasterxml.jackson.databind.JsonNode node, String source) {
        switch (source) {
            case "claude-code" -> {
                if ("user".equals(node.path("type").asText(""))) {
                    com.fasterxml.jackson.databind.JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode block : content) {
                            String text = block.path("text").asText("");
                            // Skip Claude Code internal commands — not real user content
                            if (!text.isEmpty() && !text.startsWith("<local-command-")) return text;
                        }
                    } else if (content.isTextual()) {
                        String text = content.asText("");
                        if (!text.isEmpty() && !text.startsWith("<local-command-")) return text;
                    }
                }
            }
            case "codex" -> {
                if ("response_item".equals(node.path("type").asText(""))) {
                    if ("user".equals(node.path("payload").path("role").asText(""))) {
                        com.fasterxml.jackson.databind.JsonNode arr = node.path("payload").path("content");
                        if (arr.isArray() && arr.size() > 0) {
                            String text = arr.get(0).path("text").asText("");
                            if (!text.isEmpty()) return text;
                        }
                    }
                }
            }
            case "qwen" -> {
                if ("user".equals(node.path("type").asText(""))) {
                    com.fasterxml.jackson.databind.JsonNode parts = node.path("message").path("parts");
                    if (parts.isArray() && parts.size() > 0) {
                        String text = parts.get(0).path("text").asText("");
                        if (!text.isEmpty()) return text;
                    }
                }
            }
            case "opencode" -> {
                if ("user".equals(node.path("role").asText(""))) {
                    com.fasterxml.jackson.databind.JsonNode arr = node.path("content");
                    if (arr.isArray() && arr.size() > 0) {
                        String text = arr.get(0).path("text").asText("");
                        if (!text.isEmpty()) return text;
                    }
                }
            }
            case "gemini" -> {
                if ("user".equals(node.path("type").asText(""))) {
                    String text = node.path("content").asText("");
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return null;
    }

    /**
     * Apply current filters to the conversation list.
     */
    private void applyFilters() {
        filteredConversations = new ArrayList<>(allConversations);

        // Filter by agent (tab)
        if (!filterAgent.isEmpty()) {
            String agent = filterAgent.toLowerCase();
            filteredConversations = filteredConversations.stream()
                    .filter(c -> c.agent().toLowerCase().equals(agent))
                    .collect(Collectors.toList());
        }

        // If a search query is active, eagerly load titles for all tab-filtered conversations
        // that still have an empty title (lazy-loaded placeholders). Without this, external
        // conversations (claude-code, codex, qwen, gemini, opencode) would never match because
        // their titles are "" until loadTitlesForVisiblePage() runs — which is too late.
        // Uses parallel loading and O(1) index lookup for performance.
        if (!searchQuery.isEmpty()) {
            // Collect indices that need title loading
            List<Integer> needTitles = new ArrayList<>();
            for (int i = 0; i < filteredConversations.size(); i++) {
                ConversationSummary convo = filteredConversations.get(i);
                if (convo.title() != null && convo.title().isEmpty()) {
                    needTitles.add(i);
                }
            }

            if (!needTitles.isEmpty()) {
                // Build O(1) index for allConversations persistence
                Map<String, Integer> allConvoIndex = new HashMap<>();
                for (int j = 0; j < allConversations.size(); j++) {
                    allConvoIndex.put(allConversations.get(j).sessionId(), j);
                }

                // Load titles in parallel (file path lookup is O(1) via sessionFilePaths cache)
                Map<Integer, String> loadedTitles = needTitles.parallelStream()
                        .collect(Collectors.toConcurrentMap(
                                i -> i,
                                i -> {
                                    ConversationSummary c = filteredConversations.get(i);
                                    return loadTitleForConversation(c.sessionId(), c.source());
                                }
                        ));

                // Apply loaded titles to both lists
                for (Map.Entry<Integer, String> entry : loadedTitles.entrySet()) {
                    int i = entry.getKey();
                    ConversationSummary convo = filteredConversations.get(i);
                    ConversationSummary updated = new ConversationSummary(
                            convo.sessionId(), entry.getValue(), convo.started(),
                            convo.agent(), convo.source(), convo.lastModified(),
                            convo.lastModifiedTimestamp());
                    filteredConversations.set(i, updated);
                    Integer allIdx = allConvoIndex.get(convo.sessionId());
                    if (allIdx != null) {
                        allConversations.set(allIdx, updated);
                    }
                }
            }
        }

        // Filter by search query
        if (!searchQuery.isEmpty()) {
            String query = searchQuery.toLowerCase();
            filteredConversations = filteredConversations.stream()
                    .filter(c -> c.title().toLowerCase().contains(query) ||
                                 c.sessionId().toLowerCase().contains(query) ||
                                 c.agent().toLowerCase().contains(query))
                    .collect(Collectors.toList());
        }

        // Filter by source
        if (!filterSource.isEmpty()) {
            String source = filterSource.toLowerCase();
            filteredConversations = filteredConversations.stream()
                    .filter(c -> c.source().toLowerCase().contains(source))
                    .collect(Collectors.toList());
        }

        // Apply sorting
        applySorting();
    }

    /**
     * Apply current sort settings to filtered conversations.
     */
    private void applySorting() {
        Comparator<ConversationSummary> comparator;
        
        switch (sortField) {
            case "date":
                // Sort by lastModified timestamp
                comparator = (a, b) -> sortAscending ? 
                        Long.compare(a.lastModifiedTimestamp(), b.lastModifiedTimestamp()) : 
                        Long.compare(b.lastModifiedTimestamp(), a.lastModifiedTimestamp());
                break;
            case "title":
                comparator = (a, b) -> {
                    String titleA = a.title() != null && !a.title().isEmpty() ? a.title() : a.sessionId();
                    String titleB = b.title() != null && !b.title().isEmpty() ? b.title() : b.sessionId();
                    return sortAscending ? titleA.compareToIgnoreCase(titleB) : titleB.compareToIgnoreCase(titleA);
                };
                break;
            case "agent":
                comparator = (a, b) -> {
                    int cmp = sortAscending ? 
                            a.agent().compareToIgnoreCase(b.agent()) : 
                            b.agent().compareToIgnoreCase(a.agent());
                    if (cmp != 0) return cmp;
                    // Secondary sort by date (most recent first)
                    return Long.compare(b.lastModifiedTimestamp(), a.lastModifiedTimestamp());
                };
                break;
            default:
                comparator = Comparator.comparing(ConversationSummary::sessionId);
                break;
        }
        
        filteredConversations.sort(comparator);
    }

    /**
     * Set the agent filter based on the current tab index.
     */
    private void setFilterForCurrentTab() {
        List<String> agents = allConversations.stream()
                .map(ConversationSummary::agent)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        if (currentTab >= 0 && currentTab < agents.size()) {
            filterAgent = agents.get(currentTab);
        }
    }

    /**
     * Re-apply filters and load titles for the visible page.
     */
    private void refreshView() {
        applyFilters();
        loadTitlesForVisiblePage();
    }

    /**
     * Render the main view with tabs and conversation list.
     */
    private void renderMainView() {
        terminal.writer().print("\033[2J\033[H"); // clear screen + cursor home
        terminal.writer().println();

        // Header
        terminal.writer().println(BOLD + CYAN + "╔══════════════════════════════════════════════════════════╗" + RESET);
        terminal.writer().println(BOLD + CYAN + "║" + WHITE + "           Kompile Conversation Resume Tool               " + CYAN + "║" + RESET);
        terminal.writer().println(BOLD + CYAN + "╚══════════════════════════════════════════════════════════╝" + RESET);
        terminal.writer().println();

        // Tabs
        renderTabs();
        terminal.writer().println();

        // Filters info
        terminal.writer().println(DIM + "Filters: " + RESET);
        terminal.writer().println(DIM + "  Scope: " + RESET + GREEN
                + (localOnly ? "local" : "all projects")
                + RESET + DIM + (localOnly ? " (use 'all' for global)" : " (use 'local' to filter)") + RESET);
        if (!searchQuery.isEmpty()) {
            terminal.writer().println(DIM + "  Search: " + RESET + GREEN + searchQuery + RESET);
        }
        if (!filterAgent.isEmpty()) {
            terminal.writer().println(DIM + "  Agent: " + RESET + GREEN + filterAgent + RESET);
        }
        if (!filterSource.isEmpty()) {
            terminal.writer().println(DIM + "  Source: " + RESET + GREEN + filterSource + RESET);
        }

        // Sort info
        String sortDirection = sortAscending ? "asc" : "desc";
        terminal.writer().println(DIM + "  Sort: " + RESET + GREEN + sortField + " " + sortDirection + RESET);
        terminal.writer().println();

        // Conversation list
        renderConversationList();
        terminal.writer().println();

        // Pagination
        renderPagination();
        terminal.writer().println();

        // Commands
        terminal.writer().println(DIM + "Commands: <agent#> (1-5) or tab <n> | search <query> | filter agent=<name> | sort <field> [asc|desc] | all | local | page <n> | view <id> | resume <id> [uuid] | next | prev | clear | q" + RESET);
        terminal.writer().println();
        terminal.writer().flush();
    }

    /**
     * Render tab bar for different agent groups with numbered selection.
     */
    private void renderTabs() {
        Set<String> agents = allConversations.stream()
                .map(ConversationSummary::agent)
                .collect(Collectors.toSet());

        List<String> sortedAgents = agents.stream().sorted().collect(Collectors.toList());
        int tabIndex = 0;
        for (String agent : sortedAgents) {
            String tabLabel = agent.length() > 14 ? agent.substring(0, 14) : agent;
            if (tabIndex == currentTab) {
                terminal.writer().print(BOLD + GREEN + " [" + (tabIndex + 1) + " " + tabLabel + "]" + RESET + " ");
            } else {
                terminal.writer().print(DIM + (tabIndex + 1) + " " + tabLabel + DIM + "  " + RESET);
            }
            tabIndex++;
        }
        terminal.writer().println();
    }

    /**
     * Render the current page of conversations.
     * Lazily loads titles for external conversations on the visible page only.
     */
    private void renderConversationList() {
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filteredConversations.size());

        if (start >= filteredConversations.size()) {
            terminal.writer().println(YELLOW + "  No conversations to display." + RESET);
            return;
        }

        // Header - show title instead of ID
        terminal.writer().println(BOLD + "  #" + DIM + "  Title" + " ".repeat(54) + "Agent" + " ".repeat(14) + "Date" + RESET);
        terminal.writer().println(DIM + "  " + "─".repeat(100) + RESET);

        // Conversations - load titles lazily for external conversations on this page only
        for (int i = start; i < end; i++) {
            ConversationSummary convo = filteredConversations.get(i);
            int num = i - start + 1;
            String title = convo.title() != null && !convo.title().isEmpty() ? convo.title() :
                    convo.agent() + " session " + convo.sessionId().substring(0, Math.min(8, convo.sessionId().length()));
            String titleDisplay = title.length() > 60 ? title.substring(0, 57) + "..." : title;
            String agentDisplay = convo.agent().length() > 14 ? convo.agent().substring(0, 14) : convo.agent();
            String dateDisplay = convo.lastModified() != null ? convo.lastModified() : "";

            String color = (num % 2 == 0) ? WHITE : DIM;
            terminal.writer().println(color + String.format("  %2d %-60s  %-14s  %s",
                    num, titleDisplay, agentDisplay, dateDisplay) + RESET);
        }
    }

    /**
     * Load titles for external conversations on the current visible page.
     * Called after filtering to populate titles for display.
     */
    private void loadTitlesForVisiblePage() {
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filteredConversations.size());

        // Collect page items needing titles
        List<Integer> needTitles = new ArrayList<>();
        for (int i = start; i < end; i++) {
            ConversationSummary convo = filteredConversations.get(i);
            if (convo.title() != null && convo.title().isEmpty()) {
                needTitles.add(i);
            }
        }
        if (needTitles.isEmpty()) return;

        // Build O(1) index for allConversations persistence
        Map<String, Integer> allConvoIndex = new HashMap<>();
        for (int j = 0; j < allConversations.size(); j++) {
            allConvoIndex.put(allConversations.get(j).sessionId(), j);
        }

        // Load titles (small batch — up to PAGE_SIZE items)
        for (int i : needTitles) {
            ConversationSummary convo = filteredConversations.get(i);
            String title = loadTitleForConversation(convo.sessionId(), convo.source());
            ConversationSummary updated = new ConversationSummary(
                    convo.sessionId(), title, convo.started(),
                    convo.agent(), convo.source(), convo.lastModified(),
                    convo.lastModifiedTimestamp());
            filteredConversations.set(i, updated);
            Integer allIdx = allConvoIndex.get(convo.sessionId());
            if (allIdx != null) {
                allConversations.set(allIdx, updated);
            }
        }
    }

    /**
     * Render pagination controls.
     */
    private void renderPagination() {
        int totalPages = (int) Math.ceil((double) filteredConversations.size() / PAGE_SIZE);
        int currentPageNum = currentPage + 1;

        terminal.writer().println(DIM + String.format("  Page %d of %d (%d conversations)",
                currentPageNum, totalPages, filteredConversations.size()) + RESET);
    }

    /**
     * Switch to a different tab (agent group).
     * Expects 1-based display numbers to match the tab bar.
     */
    private void switchTab(String tabStr) {
        try {
            int displayNum = Integer.parseInt(tabStr);
            List<String> agents = allConversations.stream()
                    .map(ConversationSummary::agent)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            // Convert 1-based display number to 0-based index
            int tabIndex = displayNum - 1;

            if (tabIndex >= 0 && tabIndex < agents.size()) {
                filterAgent = agents.get(tabIndex);
                currentTab = tabIndex;
                currentPage = 0;
                refreshView();
            } else {
                terminal.writer().println(RED + "Invalid tab number: " + displayNum + " (1-" + agents.size() + ")" + RESET);
            }
        } catch (NumberFormatException e) {
            terminal.writer().println(RED + "Invalid tab number: " + tabStr + RESET);
        }
    }

    /**
     * Handle filter commands.
     */
    private void handleFilter(String filterStr) {
        if (filterStr.startsWith("agent=")) {
            filterAgent = filterStr.substring(6);
        } else if (filterStr.startsWith("source=")) {
            filterSource = filterStr.substring(7);
        } else {
            terminal.writer().println(RED + "Unknown filter type. Use: agent=<name> or source=<name>" + RESET);
        }
        currentPage = 0;
        refreshView();
    }

    /**
     * Handle sort commands.
     * Supported formats:
     *   sort date           - Sort by date descending (most recent first)
     *   sort date asc       - Sort by date ascending (oldest first)
     *   sort date desc      - Sort by date descending (most recent first)
     *   sort title          - Sort by title ascending (A-Z)
     *   sort title desc     - Sort by title descending (Z-A)
     *   sort agent          - Sort by agent name ascending
     *   sort agent desc     - Sort by agent name descending
     */
    private void handleSort(String sortStr) {
        String[] parts = sortStr.trim().split("\\s+", 2);
        String field = parts[0].toLowerCase();
        String direction = parts.length > 1 ? parts[1].toLowerCase() : "";

        switch (field) {
            case "date":
            case "time":
            case "modified":
                sortField = "date";
                sortAscending = direction.equals("asc");
                break;
            case "title":
            case "name":
                sortField = "title";
                sortAscending = !direction.equals("desc"); // default: asc for title
                break;
            case "agent":
            case "source":
                sortField = "agent";
                sortAscending = !direction.equals("desc"); // default: asc for agent
                break;
            default:
                terminal.writer().println(RED + "Unknown sort field: " + field + " (use: date, title, agent)" + RESET);
                terminal.writer().flush();
                return;
        }

        currentPage = 0;
        refreshView();
    }

    /**
     * Clear all active filters.
     */
    private void clearFilters() {
        searchQuery = "";
        filterSource = "";
        currentTab = 0;
        sortField = "date";
        sortAscending = false;
        currentPage = 0;
        setFilterForCurrentTab();
        refreshView();
    }

    /**
     * Navigate to a specific page number.
     */
    private void goToPage(String pageStr) {
        try {
            int pageNum = Integer.parseInt(pageStr);
            int totalPages = (int) Math.ceil((double) filteredConversations.size() / PAGE_SIZE);
            
            if (pageNum < 1) {
                pageNum = 1;
            } else if (pageNum > totalPages) {
                pageNum = totalPages;
            }
            
            currentPage = pageNum - 1; // Convert 1-based to 0-based
            loadTitlesForVisiblePage();
        } catch (NumberFormatException e) {
            terminal.writer().println(RED + "Invalid page number: " + pageStr + RESET);
            terminal.writer().flush();
        }
    }

    /**
     * Navigate to next page.
     */
    private void nextPage() {
        int totalPages = (int) Math.ceil((double) filteredConversations.size() / PAGE_SIZE);
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadTitlesForVisiblePage();
        }
    }

    /**
     * Navigate to previous page.
     */
    private void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            loadTitlesForVisiblePage();
        }
    }

    /**
     * View a specific conversation transcript.
     */
    private void viewConversation(String sessionId) {
        try {
            terminal.writer().print("\033[2J"); terminal.writer().flush();
            terminal.writer().println(BOLD + CYAN + "Conversation: " + sessionId + RESET);
            terminal.writer().println(DIM + "─".repeat(80) + RESET);
            terminal.writer().println();

            // Try to load via the unified loader (supports kompile + all external sources)
            LoadedConversation conversation = null;
            try {
                conversation = loadConversation(sessionId);
            } catch (Exception ignored) {}

            if (conversation != null && conversation.turns() != null && !conversation.turns().isEmpty()) {
                // Format and display turns
                int lineCount = 0;
                int maxLines = 50;
                for (ChatHistory.Turn turn : conversation.turns()) {
                    if (lineCount >= maxLines) break;
                    String roleColor = "user".equals(turn.role()) ? GREEN : CYAN;
                    terminal.writer().println(roleColor + BOLD + turn.role().toUpperCase() + ":" + RESET);
                    String[] contentLines = turn.content().split("\n");
                    for (String line : contentLines) {
                        if (lineCount >= maxLines) break;
                        terminal.writer().println("  " + line);
                        lineCount++;
                    }
                    terminal.writer().println();
                    lineCount++;
                }
                if (lineCount >= maxLines) {
                    terminal.writer().println();
                    terminal.writer().println(DIM + "... (more content available, showing first " + maxLines + " lines)" + RESET);
                }
            } else {
                terminal.writer().println(YELLOW + "No transcript found for session: " + sessionId + RESET);
            }

            terminal.writer().println();
            terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
            lineReader.readLine();
        } catch (Exception e) {
            terminal.writer().println(RED + "Error viewing conversation: " + e.getMessage() + RESET);
        }
    }

    /**
     * Get the path to a transcript file.
     */
    private static Path getTranscriptPath(String sessionId) {
        return Paths.get(System.getProperty("user.home"), ".kompile", "conversations", sessionId + ".txt");
    }

    /**
     * Migrate a conversation to a different format.
     */
    private void migrateConversation(String sessionId) {
        try {
            terminal.writer().println();
            terminal.writer().println(BOLD + MAGENTA + "Migrating conversation: " + sessionId + RESET);
            terminal.writer().println();

            // Ask for target format
            String format = lineReader.readLine("Target format (kompile/openai/anthropic/markdown/jsonl) [kompile]: ");
            if (format.isEmpty()) {
                format = "kompile";
            }

            // Load turns and format them
            List<ChatHistory.Turn> turns = reader.readKompileSession(sessionId);
            String migrated = ConversationFormatter.format(turns, format);
            
            if (migrated != null) {
                // Save migrated transcript
                Path outputPath = Paths.get(System.getProperty("user.home"), ".kompile", "conversations",
                        sessionId + "-migrated." + format);
                java.nio.file.Files.writeString(outputPath, migrated);

                terminal.writer().println(GREEN + "✓ Conversation migrated to " + format + " format" + RESET);
                terminal.writer().println(DIM + "  Saved to: " + outputPath + RESET);
            } else {
                terminal.writer().println(RED + "✗ Migration failed" + RESET);
            }

            terminal.writer().println();
            terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
            lineReader.readLine();
        } catch (Exception e) {
            terminal.writer().println(RED + "Error migrating conversation: " + e.getMessage() + RESET);
        }
    }

    /**
     * Resume a conversation with a designated agent via native session injection.
     * Shows a simple menu first: Resume, Migrate, or View.
     *
     * @param sessionId the source session to resume
     * @param selectedAgent the agent tab currently selected (used as default agent)
     * @param targetUuid optional UUID to use as the target session ID; if provided and the user
     *                   chooses option 1, the conversation is exported with this UUID directly
     */
    private void resumeConversation(String sessionId, String selectedAgent, String targetUuid) {
        try {
            // Find the conversation summary to get the source, and lazily load title
            String convoSource = "external";
            String displayTitle = sessionId;
            for (ConversationSummary cs : allConversations) {
                if (cs.sessionId().equals(sessionId)) {
                    convoSource = cs.source();
                    if (cs.title() != null && !cs.title().isEmpty()) {
                        displayTitle = cs.title();
                    } else {
                        // Lazy load title for external conversations
                        displayTitle = loadTitleForConversation(sessionId, convoSource);
                    }
                    break;
                }
            }

            terminal.writer().println();
            terminal.writer().println(BOLD + GREEN + "Conversation: " + sessionId + RESET);
            terminal.writer().println(DIM + "  " + displayTitle + RESET);
            terminal.writer().println();
            terminal.writer().println("What would you like to do?");
            terminal.writer().println("  " + CYAN + "1" + RESET + "  Resume normally (launch same agent, continue conversation)");
            terminal.writer().println("  " + CYAN + "2" + RESET + "  Resume with different agent (export & launch with another agent)");
            terminal.writer().println("  " + CYAN + "3" + RESET + "  Resume with specific UUID (specify the target session ID)");
            terminal.writer().println("  " + CYAN + "4" + RESET + "  Migrate to different format (export only, don't launch)");
            terminal.writer().println("  " + CYAN + "5" + RESET + "  View transcript");
            terminal.writer().println("  " + CYAN + "6" + RESET + "  Cancel");
            terminal.writer().println();

            String choice = lineReader.readLine("Choice [1-6]: ");

            if (choice == null || choice.trim().isEmpty() || choice.trim().equals("6")) {
                return; // Cancel - go back to main list
            }

            if (choice.trim().equals("5")) {
                viewConversation(sessionId);
                return;
            }

            if (choice.trim().equals("4")) {
                migrateConversation(sessionId);
                return;
            }

            LoadedConversation conversation = null;
            String loadError = null;
            try {
                conversation = loadConversation(sessionId);
            } catch (Exception e) {
                loadError = e.getMessage();
            }

            if (conversation == null || conversation.turns() == null || conversation.turns().isEmpty()) {
                if (loadError != null && !loadError.isBlank()) {
                    terminal.writer().println(DIM + "  [debug: " + loadError + "]" + RESET);
                }
                terminal.writer().println(RED + "✗ No transcript found for session: " + sessionId + RESET);
                terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
                lineReader.readLine();
                return;
            }

            terminal.writer().println(GREEN + "✓ Loaded " + conversation.turns().size() + " turns from " + conversation.source() + RESET);
            terminal.writer().println();

            if (choice.trim().equals("1")) {
                // Option 1: Resume normally — same agent, use native resume directly
                String agent = (selectedAgent != null && !selectedAgent.isBlank())
                        ? selectedAgent
                        : determineAgentFromSource(conversation.source());
                String sourceAgent = determineAgentFromSource(conversation.source());

                // Same agent → skip export, just launch with native resume flag
                if (targetUuid == null && sourceAgent.equals(agent)) {
                    terminal.writer().println(CYAN + "Resuming natively with " + agent + "..." + RESET);
                    terminal.writer().flush();
                    launchNativeResume(agent, sessionId, conversation.workingDirectory());
                } else {
                    // Cross-agent or explicit UUID override → export needed
                    if (targetUuid != null) {
                        terminal.writer().println(CYAN + "Resuming with " + agent + " using session " + targetUuid + "..." + RESET);
                    } else {
                        terminal.writer().println(CYAN + "Exporting and resuming with " + agent + "..." + RESET);
                    }
                    launchAgentWithSession(conversation, agent, targetUuid);
                }
                return;
            }

            if (choice.trim().equals("2")) {
                // Option 2: Resume with different agent
                String currentAgent = determineAgentFromSource(conversation.source());
                String agent = lineReader.readLine("Target agent (current: " + currentAgent + ") [claude]: ");
                if (agent.isEmpty()) {
                    agent = "claude";
                }
                terminal.writer().println(CYAN + "Exporting conversation to " + agent + " native format..." + RESET);
                launchAgentWithSession(conversation, agent, null);
                return;
            }

            if (choice.trim().equals("3")) {
                // Option 3: Resume with specific UUID
                String currentAgent = determineAgentFromSource(conversation.source());
                String agent = lineReader.readLine("Target agent (current: " + currentAgent + ") [claude]: ");
                if (agent.isEmpty()) {
                    agent = "claude";
                }
                String inputUuid = lineReader.readLine("Target session UUID: ");
                if (inputUuid == null || inputUuid.trim().isEmpty()) {
                    terminal.writer().println(YELLOW + "No UUID provided. Returning to main list." + RESET);
                    terminal.writer().flush();
                    return;
                }
                terminal.writer().println(CYAN + "Exporting conversation to " + agent + " with session " + inputUuid.trim() + "..." + RESET);
                launchAgentWithSession(conversation, agent, inputUuid.trim());
                return;
            }

            terminal.writer().println(YELLOW + "Invalid choice. Returning to main list." + RESET);
            terminal.writer().flush();
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
        } catch (UserInterruptException e) {
            // User pressed Ctrl+C - return to main list quietly
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            try {
                terminal.writer().println(RED + "Error resuming conversation: " + msg + RESET);
                terminal.writer().flush();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Determine the original agent from the conversation source.
     */
    private String determineAgentFromSource(String source) {
        switch (source) {
            case "claude-code":
            case "claude":
                return "claude";
            case "codex":
                return "codex";
            case "gemini":
                return "gemini";
            case "qwen":
                return "qwen";
            case "opencode":
                return "opencode";
            default:
                return "claude";
        }
    }

    /**
     * Launch an agent with its native resume command — no export, no conversion.
     * Used when resuming a conversation back into the same agent that created it.
     * The session file already exists in the agent's native format.
     */
    private void launchNativeResume(String agent, String sessionId, Path workingDirectory) {
        boolean terminalClosed = false;
        java.nio.file.Path injectedSettingsFile = null;
        try {
            Path effectiveWorkDir = workingDirectory != null
                    ? workingDirectory
                    : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

            // Build the native resume command for this agent
            List<String> agentCommand = new ArrayList<>();
            switch (agent.toLowerCase()) {
                case "claude", "claude-code" -> {
                    agentCommand.add("claude");
                    agentCommand.add("--resume");
                    agentCommand.add(sessionId);
                }
                case "codex" -> {
                    agentCommand.add("codex");
                    agentCommand.add("resume");
                    agentCommand.add("--all");
                    agentCommand.add(sessionId);
                    agentCommand.add("-C");
                    agentCommand.add(effectiveWorkDir.toString());
                }
                case "qwen" -> {
                    agentCommand.add("qwen");
                    agentCommand.add("--resume");
                    agentCommand.add(sessionId);
                }
                case "opencode" -> {
                    agentCommand.add("opencode");
                    agentCommand.add("-s");
                    agentCommand.add(sessionId);
                }
                case "gemini" -> {
                    // Gemini uses index-based resume, not session ID — fall through to export path
                    terminal.writer().println(YELLOW + "Gemini requires index-based resume — exporting instead." + RESET);
                    terminal.writer().flush();
                    // launchAgentWithSession manages its own terminal lifecycle
                    LoadedConversation conv = loadConversation(sessionId);
                    launchAgentWithSession(conv, agent, null);
                    return;
                }
                default -> {
                    terminal.writer().println(RED + "Unknown agent for native resume: " + agent + RESET);
                    terminal.writer().flush();
                    return;
                }
            }

            // Add permission bypass flags
            ai.kompile.cli.main.chat.agent.AgentFlagOverrides.addPermissionBypassFlags(
                    agentCommand, agent, true, effectiveWorkDir);

            // Inject MCP tools before launching — probe for running kompile-app (SSE),
            // fall back to stdio if not found
            String sseUrl = ai.kompile.cli.main.chat.McpUrlResolver.resolveOnce(null, 0);
            try {
                injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                        effectiveWorkDir, agent, sseUrl);
            } catch (Exception e) {
                terminal.writer().println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
            }

            String mcpMode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
            terminal.writer().println(DIM + "  MCP: " + mcpMode + RESET);
            terminal.writer().println(DIM + "  Command: " + String.join(" ", agentCommand) + RESET);
            terminal.writer().println();
            terminal.writer().flush();

            // Close terminal and launch agent
            terminal.close();
            terminalClosed = true;

            ProcessBuilder pb = new ProcessBuilder(agentCommand);
            pb.directory(effectiveWorkDir.toFile());
            pb.inheritIO();
            Process process = pb.start();

            boolean interrupted = false;
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException ie) {
                // Ctrl+C hit the JVM thread — kill the subprocess
                interrupted = true;
                process.destroy();
                try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (process.isAlive()) process.destroyForcibly();
                exitCode = 130; // conventional SIGINT exit code
                Thread.interrupted(); // clear interrupt flag
            } finally {
                // Always clean up MCP tools
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
            }

            // Clean up terminal state after agent exits
            System.out.print("\033[0m");
            System.out.print("\033[?25h");
            System.out.println();
            System.out.println();
            System.out.flush();

            // Re-open terminal
            Terminal newTerminal = TerminalBuilder.builder().system(true).build();
            LineReader newLineReader = LineReaderBuilder.builder().terminal(newTerminal).build();
            this.terminal = newTerminal;
            this.lineReader = newLineReader;
            terminalClosed = false;

            newTerminal.writer().println();
            if (interrupted) {
                newTerminal.writer().println(YELLOW + "Agent interrupted — returned to Kompile." + RESET);
            } else {
                newTerminal.writer().println(GREEN + "✓ Agent session completed (exit code: " + exitCode + ")" + RESET);
            }
            newTerminal.writer().println(DIM + "Press Enter to continue..." + RESET);
            newTerminal.writer().flush();
            try { newLineReader.readLine(); } catch (UserInterruptException | EndOfFileException ignored) {}
        } catch (UserInterruptException e) {
            // Ctrl+C before subprocess launched (during terminal input)
            if (terminalClosed) {
                restoreTerminalAfterLaunchFailure();
            }
        } catch (Exception e) {
            // Clean up MCP tools on error path too
            ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);

            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean restored = !terminalClosed || restoreTerminalAfterLaunchFailure();
            if (restored) {
                terminal.writer().println(RED + "Error launching agent: " + errorMsg + RESET);
                terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
                terminal.writer().flush();
                try { lineReader.readLine(); } catch (Exception ignored) {}
            } else {
                System.err.println("Error launching agent: " + errorMsg);
            }
        }
    }

    /**
     * Export conversation and launch agent with native session resume.
     * Used for cross-agent resume (source != target) where format conversion is needed.
     *
     * @param conversation the loaded conversation to resume
     * @param agent the target agent to launch
     * @param targetSessionId optional UUID to use as the session ID for the exported conversation;
     *                        if null, a new UUID is generated
     */
    private void launchAgentWithSession(LoadedConversation conversation, String agent, String targetSessionId) {
        boolean terminalClosed = false;
        java.nio.file.Path injectedSettingsFile = null;
        try {
            // Export to agent's native format
            ConversationExporter.ExportResult exportResult;
            try {
                exportResult = ConversationExporter.exportToAgent(
                        conversation.turns(),
                        agent,
                        targetSessionId,
                        conversation.source(),
                        conversation.workingDirectory());
            } catch (Exception e) {
                terminal.writer().println(RED + "✗ Export failed: " + e.getMessage() + RESET);
                terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
                lineReader.readLine();
                return;
            }

            terminal.writer().println(GREEN + "✓ Exported to " + agent + " native format" + RESET);
            terminal.writer().println(DIM + "  Session ID: " + exportResult.getSessionId() + RESET);
            terminal.writer().println(DIM + "  Saved to: " + exportResult.getSessionPath() + RESET);
            terminal.writer().println(DIM + "  Resume command: " + exportResult.getResumeCommand() + RESET);
            terminal.writer().println();
            terminal.writer().println(DIM + "Launching agent with native session resume..." + RESET);
            terminal.writer().println();
            terminal.writer().flush();

            // Inject MCP tools before launching — probe for running kompile-app (SSE),
            // fall back to stdio if not found
            java.nio.file.Path agentWorkingDir = exportResult.getWorkingDirectory() != null
                    ? exportResult.getWorkingDirectory()
                    : java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            String sseUrl = ai.kompile.cli.main.chat.McpUrlResolver.resolveOnce(null, 0);
            try {
                injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                        agentWorkingDir, agent, sseUrl);
            } catch (Exception e) {
                terminal.writer().println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
            }

            // Close terminal and launch agent with native resume
            terminal.close();
            terminalClosed = true;

            List<String> agentCommand = new ArrayList<>();
            String[] resumeParts = exportResult.getResumeCommand().split("\\s+");
            if (resumeParts.length == 0 || resumeParts[0].isEmpty()) {
                throw new IllegalArgumentException("Resume command is empty for agent: " + agent);
            }
            agentCommand.add(resumeParts[0]);
            if (agent.toLowerCase().contains("codex") && exportResult.getWorkingDirectory() != null) {
                agentCommand.add("-C");
                agentCommand.add(exportResult.getWorkingDirectory().toString());
            }
            for (int i = 1; i < resumeParts.length; i++) {
                String part = resumeParts[i];
                if (!part.isEmpty()) {
                    agentCommand.add(part);
                }
            }

            // Add permission bypass flags via centralized overrides
            ai.kompile.cli.main.chat.agent.AgentFlagOverrides.addPermissionBypassFlags(
                    agentCommand, agent, true, exportResult.getWorkingDirectory());

            ProcessBuilder pb = new ProcessBuilder(agentCommand);
            if (exportResult.getWorkingDirectory() != null) {
                pb.directory(exportResult.getWorkingDirectory().toFile());
            }
            pb.inheritIO();
            Process process = pb.start();

            boolean interrupted = false;
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException ie) {
                // Ctrl+C hit the JVM thread — kill the subprocess
                interrupted = true;
                process.destroy();
                try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (process.isAlive()) process.destroyForcibly();
                exitCode = 130;
                Thread.interrupted(); // clear interrupt flag
            } finally {
                // Always clean up MCP tools
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
            }

            // Clean up terminal state after agent exits
            System.out.print("\033[0m"); // reset colors
            System.out.print("\033[?25h"); // show cursor
            System.out.println();
            System.out.println();
            System.out.flush();

            // Re-open terminal after agent exits and restore state
            Terminal newTerminal = TerminalBuilder.builder().system(true).build();
            LineReader newLineReader = LineReaderBuilder.builder().terminal(newTerminal).build();

            // Update our terminal and lineReader references for continued use
            this.terminal = newTerminal;
            this.lineReader = newLineReader;
            terminalClosed = false;

            newTerminal.writer().println();
            if (interrupted) {
                newTerminal.writer().println(YELLOW + "Agent interrupted — returned to Kompile." + RESET);
            } else {
                newTerminal.writer().println(GREEN + "✓ Agent session completed (exit code: " + exitCode + ")" + RESET);
            }
            newTerminal.writer().println(DIM + "Press Enter to continue..." + RESET);
            newTerminal.writer().flush();
            try { newLineReader.readLine(); } catch (UserInterruptException | EndOfFileException ignored) {}
        } catch (UserInterruptException e) {
            // Ctrl+C before subprocess launched (during terminal input)
            if (terminalClosed) {
                restoreTerminalAfterLaunchFailure();
            }
        } catch (Exception e) {
            // Clean up MCP tools on error path too
            ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);

            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean restored = !terminalClosed || restoreTerminalAfterLaunchFailure();
            if (restored) {
                terminal.writer().println(RED + "Error launching agent: " + errorMsg + RESET);
                terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
                terminal.writer().flush();
                try {
                    lineReader.readLine();
                } catch (Exception ignored) {
                    // Best effort only after terminal recovery.
                }
            } else {
                System.err.println("Error launching agent: " + errorMsg);
            }
        }
    }

    private boolean restoreTerminalAfterLaunchFailure() {
        try {
            System.out.print("\033[0m");
            System.out.print("\033[?25h");
            System.out.println();
            System.out.flush();
        } catch (Exception ignored) {
            // Best effort only.
        }

        try {
            Terminal newTerminal = TerminalBuilder.builder().system(true).build();
            LineReader newLineReader = LineReaderBuilder.builder().terminal(newTerminal).build();
            this.terminal = newTerminal;
            this.lineReader = newLineReader;
            return true;
        } catch (IOException ioException) {
            return false;
        }
    }

    private LoadedConversation loadConversation(String sessionId) throws IOException {
        Path defaultWorkingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        // First, check if this is a known kompile session (file exists)
        boolean kompileSessionExists = ChatHistory.exists(sessionId);
        try {
            List<ChatHistory.Turn> turns = reader.readKompileSession(sessionId);
            if (turns != null && !turns.isEmpty()) {
                return new LoadedConversation(turns, "kompile", defaultWorkingDirectory);
            }
        } catch (Exception ignored) {
            // Fall through to external sources only if not a known kompile session.
        }

        // If the kompile session file exists but had no parseable turns (e.g., passthrough
        // session where transcript harvest failed), don't fall through to external sources
        // which would produce confusing errors like "Gemini session not found".
        if (kompileSessionExists) {
            throw new IOException("Session transcript is empty (transcript harvest may have failed): " + sessionId);
        }

        IOException lastError = null;
        for (String source : List.of("claude-code", "codex", "qwen", "opencode", "gemini")) {
            try {
                List<ChatHistory.Turn> turns = reader.readExternalSession(source, sessionId);
                if (turns == null || turns.isEmpty()) {
                    continue;
                }
                Path workingDirectory = ConversationReader.resolveExternalWorkingDirectory(source, sessionId);
                if (workingDirectory == null) {
                    throw new IOException("Could not resolve working directory for " + source + " session: " + sessionId);
                }
                return new LoadedConversation(turns, source, workingDirectory);
            } catch (Exception e) {
                lastError = e instanceof IOException ioException
                        ? ioException
                        : new IOException(e.getMessage(), e);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("No transcript found for session: " + sessionId);
    }

    private String readCodexSessionId(Path file) {
        // Stream line-by-line instead of reading entire file (codex sessions can be 100MB+).
        // session_meta is always near the top, so stop after a few lines.
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int linesRead = 0;
            while ((line = br.readLine()) != null && linesRead++ < 10) {
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                if ("session_meta".equals(node.path("type").asText(""))) {
                    String id = node.path("payload").path("id").asText("");
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to filename-based fallback.
        }

        String fileName = file.getFileName().toString();
        if (fileName.startsWith("rollout-") && fileName.endsWith(".jsonl")) {
            String trimmed = fileName.substring("rollout-".length(), fileName.length() - ".jsonl".length());
            if (trimmed.length() > 20) {
                return trimmed.substring(20);
            }
            return trimmed;
        }
        return null;
    }

    private String readGeminiSessionId(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            String id = root.path("sessionId").asText("");
            if (!id.isBlank()) {
                return id;
            }
        } catch (Exception ignored) {
            // Fall through to filename-based fallback.
        }

        String fileName = file.getFileName().toString();
        if (fileName.startsWith("session-") && fileName.endsWith(".json")) {
            String trimmed = fileName.substring("session-".length(), fileName.length() - ".json".length());
            int lastDash = trimmed.lastIndexOf('-');
            if (lastDash >= 0 && lastDash < trimmed.length() - 1) {
                return trimmed.substring(lastDash + 1);
            }
            return trimmed;
        }
        return null;
    }

    private Path findGeminiSessionFile(Path geminiTmpDir, String sessionId) {
        if (!Files.isDirectory(geminiTmpDir)) {
            return null;
        }

        try (java.util.stream.Stream<Path> projectDirs = Files.list(geminiTmpDir)) {
            for (Path file : projectDirs
                    .filter(Files::isDirectory)
                    .filter(pd -> !pd.getFileName().toString().equals("bin"))
                    .flatMap(pd -> {
                        Path chatsDir = pd.resolve("chats");
                        if (Files.isDirectory(chatsDir)) {
                            try {
                                return Files.list(chatsDir);
                            } catch (IOException e) {
                                return java.util.stream.Stream.empty();
                            }
                        }
                        return java.util.stream.Stream.empty();
                    })
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList()) {
                String storedId = readGeminiSessionId(file);
                if (storedId != null &&
                        (storedId.equals(sessionId) || storedId.startsWith(sessionId) || sessionId.startsWith(storedId))) {
                    return file;
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    // ─── Directory-matching helpers for local-only filtering ───────────────

    /**
     * Find Claude/Qwen project directories that match the current working directory.
     * These agents encode the path by replacing '/' with '-'.
     * Matches the exact path, parent paths, and child paths.
     */
    private List<Path> findMatchingClaudeProjectDirs(Path projectsRoot, String cwd) {
        if (!Files.isDirectory(projectsRoot)) return List.of();
        String normalized = cwd.replace("\\", "/");
        String encodedCwd = normalized.replace("/", "-");
        List<Path> matches = new ArrayList<>();

        try (java.util.stream.Stream<Path> dirs = Files.list(projectsRoot)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (!name.startsWith("-")) return;
                // Decode: -home-user-project -> /home/user/project
                // (hyphens in actual dir names cause ambiguity, but this matches
                //  the same logic used by Claude/Qwen for encoding)
                String decoded = "/" + name.substring(1).replace("-", "/");
                // Match: same dir, child dir, or parent dir
                if (normalized.equals(decoded)
                        || normalized.startsWith(decoded + "/")
                        || decoded.startsWith(normalized + "/")
                        || name.equals(encodedCwd)) {
                    matches.add(dir);
                }
            });
        } catch (IOException ignored) {}
        return matches;
    }

    /**
     * Check if a Codex session file's working directory matches the current cwd.
     * Reads only the first line (session_meta) for the cwd field.
     */
    private boolean codexSessionMatchesCwd(Path file, String cwd) {
        try (java.io.BufferedReader br = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String line = br.readLine();
            if (line != null && !line.isBlank()) {
                JsonNode node = MAPPER.readTree(line);
                if ("session_meta".equals(node.path("type").asText(""))) {
                    String sessionCwd = node.path("payload").path("cwd").asText("");
                    return cwdMatches(sessionCwd, cwd);
                }
            }
        } catch (Exception ignored) {}
        // If we can't determine cwd, include it (conservative)
        return !localOnly;
    }

    /**
     * Check if an OpenCode session file's directory matches the current cwd.
     * Reads the "directory" field from the JSON.
     */
    private boolean openCodeSessionMatchesCwd(Path file, String cwd) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            String dir = root.path("directory").asText("");
            return cwdMatches(dir, cwd);
        } catch (Exception ignored) {}
        return !localOnly;
    }

    /**
     * Check if a session's cwd matches the current working directory.
     * Matches exact path, parent, or child relationships.
     */
    private boolean cwdMatches(String sessionCwd, String cwd) {
        if (sessionCwd == null || sessionCwd.isBlank()) return false;
        String normSession = sessionCwd.replace("\\", "/");
        String normCwd = cwd.replace("\\", "/");
        return normCwd.equals(normSession)
                || normCwd.startsWith(normSession + "/")
                || normSession.startsWith(normCwd + "/");
    }

    /**
     * Compute SHA256 hex digest of a string (used for Gemini project hash matching).
     */
    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Load sessions from a Qwen chats directory.
     */
    private void loadQwenChatsDir(Path chatsDir, String agentName) {
        try (java.util.stream.Stream<Path> sessionFiles = Files.list(chatsDir)) {
            sessionFiles.filter(p -> p.toString().endsWith(".jsonl"))
                .forEach(p -> {
                    String id = p.getFileName().toString().replace(".jsonl", "");
                    // Skip sessions already harvested into a kompile transcript
                    if (harvestedExternalIds.contains(id)) return;
                    try {
                        long lastMod = Files.getLastModifiedTime(p).toMillis();
                        sessionFilePaths.put(id, p);
                        allConversations.add(new ConversationSummary(
                                id,
                                "",
                                String.valueOf(lastMod),
                                agentName.toLowerCase(),
                                "qwen",
                                formatDate(lastMod),
                                lastMod
                        ));
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    /**
     * Run search with parameters.
     */
    private ToolResult runSearch(JsonNode params) {
        String query = params.has("query") ? params.get("query").asText() : "";
        String agent = params.has("agent") ? params.get("agent").asText() : "";
        String source = params.has("source") ? params.get("source").asText() : "";

        if (!conversationsLoaded) {
            loadAllConversations();
        }
        searchQuery = query;
        filterAgent = agent;
        filterSource = source;
        applyFilters();

        ObjectMapper om = new ObjectMapper();
        ObjectNode result = om.createObjectNode();
        result.put("count", filteredConversations.size());
        result.put("hint", "Use the 'index' number with resume/view/migrate actions as session_id (e.g. session_id='1')");
        var array = result.putArray("conversations");
        for (int i = 0; i < filteredConversations.size(); i++) {
            ConversationSummary convo = filteredConversations.get(i);
            ObjectNode convoNode = array.addObject();
            convoNode.put("index", i + 1); // 1-based index for easy reference
            convoNode.put("session_id", convo.sessionId());
            convoNode.put("title", convo.title());
            convoNode.put("agent", convo.agent());
            convoNode.put("source", convo.source());
            convoNode.put("last_modified", convo.lastModified());
        }

        return ToolResult.success("Search results", result.toString());
    }

    /**
     * Run migration with parameters.
     */
    private ToolResult runMigrate(JsonNode params) {
        String sessionIdInput = params.has("session_id") ? params.get("session_id").asText() : "";
        String format = params.has("output_format") ? params.get("output_format").asText() : "kompile";

        if (sessionIdInput.isEmpty()) {
            return ToolResult.error("session_id is required for migrate action");
        }

        // Resolve numeric index to actual session ID from previous search results
        String sessionId = resolveSessionIdFromSearchResults(sessionIdInput);
        if (sessionId == null) {
            return ToolResult.error("Invalid session reference '" + sessionIdInput +
                    "'. If using a numeric index, run a search first to populate results. " +
                    "Otherwise, provide the full session UUID.");
        }

        try {
            List<ChatHistory.Turn> turns = reader.readKompileSession(sessionId);
            String migrated = ConversationFormatter.format(turns, format);
            
            if (migrated != null) {
                Path outputPath = Paths.get(System.getProperty("user.home"), ".kompile", "conversations",
                        sessionId + "-migrated." + format);
                java.nio.file.Files.writeString(outputPath, migrated);

                ObjectMapper om = new ObjectMapper();
                ObjectNode result = om.createObjectNode();
                result.put("session_id", sessionId);
                result.put("format", format);
                result.put("output_path", outputPath.toString());
                return ToolResult.success("Migration complete", result.toString());
            } else {
                return ToolResult.error("Failed to migrate conversation");
            }
        } catch (Exception e) {
            return ToolResult.error("Migration error: " + e.getMessage());
        }
    }

    /**
     * Run resume with parameters.
     * <p>
     * In MCP stdio context, we cannot launch an interactive process since stdout
     * is the JSON-RPC pipe. Instead, we export the conversation as structured JSON
     * so the calling agent can incorporate it into its context.
     */
    private ToolResult runResume(JsonNode params) {
        String sessionIdInput = params.has("session_id") ? params.get("session_id").asText() : "";
        String agent = params.has("target_agent") ? params.get("target_agent").asText() : "claude";
        String targetSessionId = params.has("target_session_id") ? params.get("target_session_id").asText() : null;

        if (sessionIdInput.isEmpty()) {
            return ToolResult.error("session_id is required for resume action");
        }

        // Resolve numeric index to actual session ID from previous search results
        String sessionId = resolveSessionIdFromSearchResults(sessionIdInput);
        if (sessionId == null) {
            return ToolResult.error("Invalid session reference '" + sessionIdInput +
                    "'. If using a numeric index, run a search first to populate results. " +
                    "Otherwise, provide the full session UUID.");
        }

        try {
            LoadedConversation conversation = loadConversation(sessionId);

            // Export conversation turns as structured JSON
            List<ChatHistory.Turn> turns = conversation.turns();
            String conversationJson = ConversationFormatter.format(turns, "openai");

            ObjectMapper om = new ObjectMapper();
            ObjectNode result = om.createObjectNode();
            result.put("session_id", sessionId);
            result.put("target_agent", agent);
            if (targetSessionId != null && !targetSessionId.isBlank()) {
                result.put("target_session_id", targetSessionId);
            }
            result.put("source", conversation.source());
            result.put("message_count", turns.size());
            result.put("conversation_history", conversationJson);

            // Include per-turn metadata for richer context injection
            var turnsArray = result.putArray("turns");
            for (ChatHistory.Turn turn : turns) {
                ObjectNode turnNode = turnsArray.addObject();
                turnNode.put("role", turn.role());
                turnNode.put("content", turn.content());
            }

            return ToolResult.success("Conversation exported for resume", result.toString());
        } catch (Exception e) {
            return ToolResult.error("Resume error: " + e.getMessage());
        }
    }

    /**
     * Run view with parameters.
     */
    private ToolResult runView(JsonNode params) {
        String sessionIdInput = params.has("session_id") ? params.get("session_id").asText() : "";

        if (sessionIdInput.isEmpty()) {
            return ToolResult.error("session_id is required for view action");
        }

        // Resolve numeric index to actual session ID from previous search results
        String sessionId = resolveSessionIdFromSearchResults(sessionIdInput);
        if (sessionId == null) {
            return ToolResult.error("Invalid session reference '" + sessionIdInput +
                    "'. If using a numeric index, run a search first to populate results. " +
                    "Otherwise, provide the full session UUID.");
        }

        try {
            LoadedConversation conversation = loadConversation(sessionId);
            String transcript = ConversationFormatter.format(conversation.turns(), "kompile");
            ObjectMapper om = new ObjectMapper();
            ObjectNode result = om.createObjectNode();
            result.put("session_id", sessionId);
            result.put("source", conversation.source());
            result.put("transcript", transcript);
            result.put("message_count", conversation.turns().size());
            return ToolResult.success("Conversation transcript", result.toString());
        } catch (Exception e) {
            return ToolResult.error("View error: " + e.getMessage());
        }
    }

    /**
     * Show help information.
     */
    private void showHelp() {
        terminal.writer().print("\033[2J"); terminal.writer().flush();
        terminal.writer().println(BOLD + CYAN + "Resume Tool Help" + RESET);
        terminal.writer().println(DIM + "─".repeat(80) + RESET);
        terminal.writer().println();
        terminal.writer().println("Commands:");
        terminal.writer().println("  " + GREEN + "<1-9>" + RESET + "                      Switch to agent tab (quick select)");
        terminal.writer().println("  " + GREEN + "tab <n>" + RESET + "                      Switch to agent tab (1-based number)");
        terminal.writer().println("  " + GREEN + "search <query>" + RESET + "                Search conversations by keyword");
        terminal.writer().println("  " + GREEN + "filter agent=<name>" + RESET + "          Filter by agent name");
        terminal.writer().println("  " + GREEN + "filter source=<name>" + RESET + "          Filter by source (kompile, claude-code, etc.)");
        terminal.writer().println("  " + GREEN + "sort date [asc|desc]" + RESET + "          Sort by date (default: desc, most recent first)");
        terminal.writer().println("  " + GREEN + "sort title [asc|desc]" + RESET + "         Sort by title (default: asc, A-Z)");
        terminal.writer().println("  " + GREEN + "sort agent [asc|desc]" + RESET + "         Sort by agent name (default: asc, with date secondary)");
        terminal.writer().println("  " + GREEN + "page <n>" + RESET + "                      Go to specific page number (1-based)");
        terminal.writer().println("  " + GREEN + "view <session-id>" + RESET + "             View conversation transcript");
        terminal.writer().println("  " + GREEN + "migrate <session-id>" + RESET + "          Migrate conversation to different format");
        terminal.writer().println("  " + GREEN + "resume <session-id>" + RESET + "           Resume conversation with designated agent");
        terminal.writer().println("  " + GREEN + "resume <session-id> <uuid>" + RESET + "    Resume with a specific target session UUID");
        terminal.writer().println("  " + GREEN + "all" + RESET + "                           Load conversations from ALL projects");
        terminal.writer().println("  " + GREEN + "local" + RESET + "                         Show only current directory's conversations");
        terminal.writer().println("  " + GREEN + "next" + RESET + "                          Next page of conversations");
        terminal.writer().println("  " + GREEN + "prev" + RESET + "                          Previous page of conversations");
        terminal.writer().println("  " + GREEN + "clear" + RESET + "                         Clear all filters and reset sort");
        terminal.writer().println("  " + GREEN + "back" + RESET + "                           Return to main menu");
        terminal.writer().println("  " + GREEN + "q" + RESET + "                             Quit the resume tool");
        terminal.writer().println();
        terminal.writer().println(DIM + "Press Enter to continue..." + RESET);
        lineReader.readLine();
    }

    /**
     * Conversation summary record for the resume tool.
     */
    private record ConversationSummary(
            String sessionId,
            String title,
            String started,
            String agent,
            String source,
            String lastModified,
            long lastModifiedTimestamp
    ) {
    }
}
