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

import java.io.IOException;
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

    private record LoadedConversation(List<ChatHistory.Turn> turns, String source, Path workingDirectory) {}

    /**
     * Constructor initializes the Resume Tool with terminal and dependencies.
     */
    public ResumeTool() throws IOException {
        this.terminal = TerminalBuilder.builder().system(true).build();
        this.lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        this.terminalRenderer = new TerminalRenderer(true);
        this.ascii = new AsciiRenderer(terminalRenderer);
        this.importTool = new ConversationImportTool();
        this.formatter = new ConversationFormatter();
        this.reader = new ConversationReader();
        this.allConversations = new ArrayList<>();
        this.filteredConversations = new ArrayList<>();
    }

    /**
     * Package-private constructor for testing with injected dependencies.
     */
    ResumeTool(Terminal terminal, LineReader lineReader, AsciiRenderer ascii,
               ConversationImportTool importTool, ConversationFormatter formatter,
               ConversationReader reader) {
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
     * Run the interactive multi-tab browser.
     */
    public ToolResult runInteractiveBrowser() {
        try {
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
                    if (line.equalsIgnoreCase("q") || line.equalsIgnoreCase("quit")) {
                        shouldQuit = true;
                        break;
                    }
                    processCommand(line);
                }
                // Render updated view BEFORE checking quit flag
                renderMainView();
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
                        String sid = resolveSessionId(rest.trim());
                        if (sid == null) {
                            terminal.writer().println(RED + "Invalid selection — number out of range." + RESET);
                            terminal.writer().flush();
                        } else {
                            resumeConversation(sid, filterAgent);
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
                case "clear":
                case "c":
                    clearFilters();
                    break;
                case "back":
                case "b":
                case "menu":
                case "setup":
                    terminal.writer().println();
                    terminal.writer().println(GREEN + "  Returning to main menu..." + RESET);
                    terminal.writer().println(DIM + "  Use: kompile chat (to start chat), kompile chat --setup (to reconfigure)" + RESET);
                    terminal.writer().println();
                    terminal.writer().flush();
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
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
     * Load all conversations from kompile and external sources.
     */
    private void loadAllConversations() {
        allConversations.clear();

        // Load kompile sessions
        try {
            List<ChatHistory.ConversationSummary> kompileSessions = ChatHistory.listConversations();
            for (ChatHistory.ConversationSummary session : kompileSessions) {
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
            terminal.writer().println(YELLOW + "Warning: Could not load kompile sessions: " + e.getMessage() + RESET);
        }

        // Load external agent conversations
        loadExternalConversations("claude-code", "claude");
        loadExternalConversations("opencode", "opencode");
        loadExternalConversations("codex", "codex");
        loadExternalConversations("qwen", "qwen");
        loadExternalConversations("gemini", "gemini");

        // Sort by last modified (most recent first)
        allConversations.sort(Comparator.comparing(ConversationSummary::lastModified).reversed());
    }

    /**
     * Format a title - extract meaningful preview from content, truncate long titles.
     */
    private String formatTitle(String title) {
        if (title == null || title.isEmpty()) return "(no title)";
        // Clean up leading whitespace, markdown headers, etc.
        String cleaned = title.trim();
        if (cleaned.startsWith("# ")) {
            cleaned = cleaned.substring(2);
        }
        // Take first line only for title
        int newlineIdx = cleaned.indexOf('\n');
        if (newlineIdx > 0) {
            cleaned = cleaned.substring(0, newlineIdx);
        }
        // Truncate to 60 chars
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 57) + "...";
        }
        return cleaned.isEmpty() ? "(no title)" : cleaned;
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
     * Does NOT read file contents for titles - uses placeholders instead for fast loading.
     */
    private void loadExternalConversations(String source, String agentName) {
        try {
            String homeDir = System.getProperty("user.home");

            switch (source) {
                case "claude-code" -> {
                    Path claudeDir = Paths.get(homeDir, ".claude", "projects");
                    if (Files.isDirectory(claudeDir)) {
                        try (java.util.stream.Stream<Path> projectDirs = Files.list(claudeDir)) {
                            projectDirs.filter(Files::isDirectory).forEach(projectDir -> {
                                try (java.util.stream.Stream<Path> sessionFiles = Files.list(projectDir)) {
                                    sessionFiles.filter(p -> p.toString().endsWith(".jsonl"))
                                        .forEach(p -> {
                                            String id = p.getFileName().toString().replace(".jsonl", "");
                                            try {
                                                long lastMod = java.nio.file.Files.getLastModifiedTime(p).toMillis();
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
                            });
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
                                    String id = readCodexSessionId(p);
                                    if (id == null || id.isBlank()) {
                                        return;
                                    }
                                    try {
                                        long lastMod = Files.getLastModifiedTime(p).toMillis();
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
                        try (java.util.stream.Stream<Path> projectDirs = Files.walk(qwenDir, 4)) {
                            projectDirs.filter(p -> p.toString().endsWith("chats") && Files.isDirectory(p))
                                .forEach(chatsDir -> {
                                    try (java.util.stream.Stream<Path> sessionFiles = Files.list(chatsDir)) {
                                        sessionFiles.filter(p -> p.toString().endsWith(".jsonl"))
                                            .forEach(p -> {
                                                String id = p.getFileName().toString().replace(".jsonl", "");
                                                try {
                                                    long lastMod = Files.getLastModifiedTime(p).toMillis();
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
                                });
                        } catch (IOException ignored) {}
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
                                            String id = p.getFileName().toString().replace(".json", "");
                                            try {
                                                long lastMod = Files.getLastModifiedTime(p).toMillis();
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
                        // Scan tmp/*/chats/session-*.json files
                        Path tmpDir = geminiDir.resolve("tmp");
                        if (Files.isDirectory(tmpDir)) {
                            try (java.util.stream.Stream<Path> projectDirs = Files.list(tmpDir)) {
                                projectDirs.filter(Files::isDirectory)
                                    .filter(pd -> !pd.getFileName().toString().equals("bin"))
                                    .forEach(projDir -> {
                                        Path chatsDir = projDir.resolve("chats");
                                        if (Files.isDirectory(chatsDir)) {
                                            try (java.util.stream.Stream<Path> sessionFiles = Files.list(chatsDir)) {
                                                sessionFiles.filter(p -> p.toString().endsWith(".json"))
                                                    .forEach(p -> {
                                                        String id = readGeminiSessionId(p);
                                                        if (id == null || id.isBlank()) {
                                                            return;
                                                        }
                                                        try {
                                                            long lastMod = Files.getLastModifiedTime(p).toMillis();
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
                                    });
                            } catch (IOException ignored) {}
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
     * Returns the formatted title or falls back to the session ID.
     */
    private String loadTitleForConversation(String sessionId, String source) {
        Path filePath = null;
        String homeDir = System.getProperty("user.home");

        try {
            switch (source) {
                case "claude-code" -> {
                    Path claudeDir = Paths.get(homeDir, ".claude", "projects");
                    if (Files.isDirectory(claudeDir)) {
                        try (java.util.stream.Stream<Path> projectDirs = Files.list(claudeDir)) {
                            filePath = projectDirs
                                .filter(Files::isDirectory)
                                .flatMap(pd -> {
                                    try { return Files.list(pd); } catch (IOException e) { return java.util.stream.Stream.empty(); }
                                })
                                .filter(p -> p.getFileName().toString().equals(sessionId + ".jsonl"))
                                .findFirst().orElse(null);
                        }
                    }
                }
                case "codex" -> {
                    Path codexDir = Paths.get(homeDir, ".codex");
                    if (Files.isDirectory(codexDir)) {
                        try (java.util.stream.Stream<Path> files = Files.walk(codexDir)) {
                            filePath = files
                                .filter(p -> p.getFileName().toString().contains(sessionId))
                                .findFirst().orElse(null);
                        }
                    }
                }
                case "qwen" -> {
                    Path qwenDir = Paths.get(homeDir, ".qwen", "projects");
                    if (Files.isDirectory(qwenDir)) {
                        try (java.util.stream.Stream<Path> projectDirs = Files.walk(qwenDir, 4)) {
                            filePath = projectDirs
                                .filter(p -> p.toString().endsWith("chats") && Files.isDirectory(p))
                                .flatMap(cd -> {
                                    try { return Files.list(cd); } catch (IOException e) { return java.util.stream.Stream.empty(); }
                                })
                                .filter(p -> p.getFileName().toString().equals(sessionId + ".jsonl"))
                                .findFirst().orElse(null);
                        }
                    }
                }
                case "opencode" -> {
                    Path sessionDir = Paths.get(homeDir, ".local", "share", "opencode", "storage", "session");
                    if (Files.isDirectory(sessionDir)) {
                        try (java.util.stream.Stream<Path> projectDirs = Files.list(sessionDir)) {
                            filePath = projectDirs
                                .filter(Files::isDirectory)
                                .flatMap(pd -> {
                                    try { return Files.list(pd); } catch (IOException e) { return java.util.stream.Stream.empty(); }
                                })
                                .filter(p -> p.getFileName().toString().equals(sessionId + ".json"))
                                .findFirst().orElse(null);
                        }
                    }
                }
                case "gemini" -> {
                    Path geminiDir = Paths.get(homeDir, ".gemini");
                    if (Files.isDirectory(geminiDir)) {
                        filePath = findGeminiSessionFile(geminiDir.resolve("tmp"), sessionId);
                    }
                }
            }

            if (filePath != null && Files.exists(filePath)) {
                return extractFirstLineFromFile(filePath, source);
            }
        } catch (Exception ignored) {}
        return "(" + source + " session)";
    }

    /**
     * Read the first user message from an external conversation file to use as a title.
     */
    private String extractFirstLineFromFile(Path filePath, String source) {
        try {
            // Gemini uses a single JSON object, not JSONL
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
                return "(gemini session)";
            }

            // All other agents use JSONL format (line-by-line JSON)
            List<String> lines = Files.readAllLines(filePath, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(line);
                    
                    // Claude Code format
                    if ("claude-code".equals(source)) {
                        String type = node.path("type").asText("");
                        if ("user".equals(type)) {
                            com.fasterxml.jackson.databind.JsonNode content = node.path("message").path("content");
                            String text = "";
                            if (content.isArray()) {
                                // Extract first text block from content array
                                for (com.fasterxml.jackson.databind.JsonNode block : content) {
                                    text = block.path("text").asText("");
                                    if (!text.isEmpty()) break;
                                }
                            } else if (content.isTextual()) {
                                text = content.asText("");
                            }
                            if (!text.isEmpty()) {
                                return formatTitle(text);
                            }
                        }
                    }
                    // Codex format
                    else if ("codex".equals(source)) {
                        String type = node.path("type").asText("");
                        if ("response_item".equals(type)) {
                            String role = node.path("payload").path("role").asText("");
                            if ("user".equals(role)) {
                                String text = node.path("payload").path("content").get(0).path("text").asText("");
                                if (!text.isEmpty()) {
                                    return formatTitle(text);
                                }
                            }
                        }
                    }
                    // Qwen format
                    else if ("qwen".equals(source)) {
                        String type = node.path("type").asText("");
                        if ("user".equals(type)) {
                            String text = node.path("message").path("parts").get(0).path("text").asText("");
                            if (!text.isEmpty()) {
                                return formatTitle(text);
                            }
                        }
                    }
                    // OpenCode format
                    else if ("opencode".equals(source)) {
                        String role = node.path("role").asText("");
                        if ("user".equals(role)) {
                            String text = node.path("content").get(0).path("text").asText("");
                            if (!text.isEmpty()) {
                                return formatTitle(text);
                            }
                        }
                    }
                    // Gemini format
                    else if ("gemini".equals(source)) {
                        String type = node.path("type").asText("");
                        if ("user".equals(type)) {
                            String text = node.path("content").asText("");
                            if (!text.isEmpty()) {
                                return formatTitle(text);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return "(" + source + " session)";
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
        terminal.writer().print("\033[H"); // cursor home (no screen clear)
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
        terminal.writer().println(DIM + "Commands: <agent#> (1-5) or tab <n> | search <query> | filter agent=<name> | sort <field> [asc|desc] | page <n> | view <id> | migrate <id> | resume <id> | next | prev | clear | back | q" + RESET);
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
                    convo.sessionId();
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

        for (int i = start; i < end; i++) {
            ConversationSummary convo = filteredConversations.get(i);
            // Only load titles for external conversations that don't have one
            if (convo.title() != null && convo.title().isEmpty()) {
                String title = loadTitleForConversation(convo.sessionId(), convo.source());
                // Update the conversation in the filtered list with the loaded title
                ConversationSummary updated = new ConversationSummary(
                        convo.sessionId(), title, convo.started(),
                        convo.agent(), convo.source(), convo.lastModified(),
                        convo.lastModifiedTimestamp());
                filteredConversations.set(i, updated);
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

            // Try to load transcript
            String transcript = loadTranscript(sessionId);
            if (transcript != null && !transcript.isEmpty()) {
                // Show first 50 lines
                String[] lines = transcript.split("\n");
                int showLines = Math.min(lines.length, 50);
                for (int i = 0; i < showLines; i++) {
                    terminal.writer().println(lines[i]);
                }
                if (lines.length > 50) {
                    terminal.writer().println();
                    terminal.writer().println(DIM + "... (" + (lines.length - 50) + " more lines)" + RESET);
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
     * Load transcript for a session.
     */
    private String loadTranscript(String sessionId) {
        try {
            // Try kompile transcript first
            Path conversationsDir = Paths.get(System.getProperty("user.home"), ".kompile", "conversations");
            Path transcriptPath = conversationsDir.resolve(sessionId + ".txt");
            if (java.nio.file.Files.exists(transcriptPath)) {
                return java.nio.file.Files.readString(transcriptPath);
            }

            // Try external sources (would need to check Claude, OpenCode, etc. directories)
            // For now, return null if not found in kompile conversations
            return null;
        } catch (Exception e) {
            return null;
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
     */
    private void resumeConversation(String sessionId, String selectedAgent) {
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
            terminal.writer().println("  " + CYAN + "3" + RESET + "  Migrate to different format (export only, don't launch)");
            terminal.writer().println("  " + CYAN + "4" + RESET + "  View transcript");
            terminal.writer().println("  " + CYAN + "5" + RESET + "  Cancel");
            terminal.writer().println();

            String choice = lineReader.readLine("Choice [1-5]: ");

            if (choice == null || choice.trim().isEmpty() || choice.trim().equals("5")) {
                return; // Cancel - go back to main list
            }

            if (choice.trim().equals("4")) {
                viewConversation(sessionId);
                return;
            }

            if (choice.trim().equals("3")) {
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
                // Option 1: Resume normally - use the agent selected in the menu
                String agent = (selectedAgent != null && !selectedAgent.isBlank())
                        ? selectedAgent
                        : determineAgentFromSource(conversation.source());
                terminal.writer().println(CYAN + "Resuming with " + agent + "..." + RESET);
                launchAgentWithSession(conversation, agent);
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
                launchAgentWithSession(conversation, agent);
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
     * Export conversation and launch agent with native session resume.
     */
    private void launchAgentWithSession(LoadedConversation conversation, String agent) {
        boolean terminalClosed = false;
        try {
            // Export to agent's native format
            ConversationExporter.ExportResult exportResult;
            try {
                exportResult = ConversationExporter.exportToAgent(
                        conversation.turns(),
                        agent,
                        null,
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

            ProcessBuilder pb = new ProcessBuilder(agentCommand);
            if (exportResult.getWorkingDirectory() != null) {
                pb.directory(exportResult.getWorkingDirectory().toFile());
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

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
            newTerminal.writer().println(GREEN + "✓ Agent session completed (exit code: " + exitCode + ")" + RESET);
            newTerminal.writer().println(DIM + "Press Enter to continue..." + RESET);
            newTerminal.writer().flush();
            newLineReader.readLine();
        } catch (UserInterruptException e) {
            // User pressed Ctrl+C to quit the agent - exit cleanly
            if (terminalClosed) {
                restoreTerminalAfterLaunchFailure();
            }
        } catch (Exception e) {
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

        try {
            List<ChatHistory.Turn> turns = reader.readKompileSession(sessionId);
            if (turns != null && !turns.isEmpty()) {
                return new LoadedConversation(turns, "kompile", defaultWorkingDirectory);
            }
        } catch (Exception ignored) {
            // Fall through to external sources.
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
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
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

    /**
     * Run search with parameters.
     */
    private ToolResult runSearch(JsonNode params) {
        String query = params.has("query") ? params.get("query").asText() : "";
        String agent = params.has("agent") ? params.get("agent").asText() : "";
        String source = params.has("source") ? params.get("source").asText() : "";

        loadAllConversations();
        searchQuery = query;
        filterAgent = agent;
        filterSource = source;
        applyFilters();

        ObjectMapper om = new ObjectMapper();
        ObjectNode result = om.createObjectNode();
        result.put("count", filteredConversations.size());
        var array = result.putArray("conversations");
        for (ConversationSummary convo : filteredConversations) {
            ObjectNode convoNode = array.addObject();
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
        String sessionId = params.has("session_id") ? params.get("session_id").asText() : "";
        String format = params.has("output_format") ? params.get("output_format").asText() : "kompile";

        if (sessionId.isEmpty()) {
            return ToolResult.error("session_id is required for migrate action");
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
        String sessionId = params.has("session_id") ? params.get("session_id").asText() : "";
        String agent = params.has("target_agent") ? params.get("target_agent").asText() : "claude";

        if (sessionId.isEmpty()) {
            return ToolResult.error("session_id is required for resume action");
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
        String sessionId = params.has("session_id") ? params.get("session_id").asText() : "";

        if (sessionId.isEmpty()) {
            return ToolResult.error("session_id is required for view action");
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
        terminal.writer().println("  " + GREEN + "tab <n>" + RESET + "                      Switch to agent tab (0-based index)");
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
