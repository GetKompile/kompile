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

package ai.kompile.app.services.diffindex;

import ai.kompile.app.services.diffpolicy.PathGlobMatcher;
import ai.kompile.cli.common.chat.sources.*;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Indexes file diffs extracted from CLI chat conversations (Claude Code, Codex,
 * OpenCode, Qwen, Cline, Cursor, Continue, Aider, Gemini, Pi, Kompile).
 *
 * Scans tool calls from CLI transcripts to find Edit/Write operations, extracts
 * file path and diff content, and persists them as searchable entries at
 * {@code ~/.kompile/agent-state/diff-index/}.
 *
 * Entries are searchable by agent, project directory, file path, and content.
 */
@Service
public class DiffIndexService {

    private static final Logger log = LoggerFactory.getLogger(DiffIndexService.class);

    private final Path indexDir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, DiffIndexEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    // Tool names that represent file edits across different CLIs
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "edit", "Edit", "write", "Write", "edit_file", "write_file",
            "write_to_file", "replace_in_file", "insert_code_block",
            "apply_diff", "create_file", "str_replace_editor",
            "file_editor", "patch", "Patch", "NotebookEdit"
    );

    // Tool names for bash/shell that might contain file writes
    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "bash", "Bash", "shell_command", "execute_command", "run_terminal_command"
    );

    public DiffIndexService() {
        this(Paths.get(System.getProperty("user.home"), ".kompile", "agent-state", "diff-index"));
    }

    /**
     * Create a diff index rooted at an explicit directory.
     *
     * <p>This constructor keeps the index usable outside a Spring application context, including
     * the stdio MCP server and focused tests. Call {@link #init()} before querying it.</p>
     */
    public DiffIndexService(Path indexDir) {
        this.indexDir = Objects.requireNonNull(indexDir, "indexDir").toAbsolutePath().normalize();
        this.mapper = JsonUtils.newStandardMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(indexDir);
            loadExisting();
            log.info("DiffIndexService initialized with {} entries from {}", entries.size(), indexDir);
        } catch (IOException e) {
            log.error("Failed to initialize diff index at {}: {}", indexDir, e.getMessage(), e);
        }
    }

    // ── Indexing from CLI transcripts ────────────────────────────────────────

    /**
     * Reindex all available CLI transcript sources. Scans each source's sessions
     * for Edit/Write tool calls and indexes them.
     *
     * @return number of new entries indexed
     */
    @Async
    public void reindexAll() {
        reindex(null);
    }

    /**
     * Reindex transcript edits for one directory-owned project.
     *
     * <p>This is the in-process MCP/local-mode entry point. Providers that can query their
     * native project index receive the directory directly; all other providers are guarded by
     * an additional path-containment filter before any transcript is read.</p>
     */
    public void reindexAll(Path workingDirectory) {
        Path projectDirectory = workingDirectory == null
                ? null : workingDirectory.toAbsolutePath().normalize();
        reindex(projectDirectory);
    }

    private void reindex(Path projectDirectory) {
        if (!indexing.compareAndSet(false, true)) {
            log.info("Indexing already in progress");
            return;
        }

        try {
            int total = 0;
            ChatSourceRegistry registry = ChatSourceRegistry.getInstance();

            for (ChatSourceAdapter adapter : registry.all()) {
                try {
                    SourceInfo info = adapter.discover();
                    if (!info.available()) continue;
                    total += indexSource(adapter, projectDirectory);
                } catch (Exception e) {
                    log.warn("Failed to index source {}: {}", adapter.id(), e.getMessage());
                }
            }

            log.info("Reindex complete: {} new entries", total);
        } finally {
            indexing.set(false);
        }
    }

    /**
     * Index a single CLI source.
     */
    public int indexSource(ChatSourceAdapter adapter) {
        return indexSource(adapter, null);
    }

    /** Index one source, optionally limited to a directory-owned project. */
    public int indexSource(ChatSourceAdapter adapter, Path projectDirectory) {
        int count = 0;
        String sourceId = adapter.id();

        try {
            List<ChatSessionSummary> sessions = projectDirectory == null
                    ? adapter.list() : adapter.list(projectDirectory);
            for (ChatSessionSummary session : sessions) {
                try {
                    if (projectDirectory != null
                            && !adapter.isWorkingDirectoryScopeAuthoritative()
                            && !belongsToProject(adapter, session, projectDirectory)) {
                        continue;
                    }
                    count += indexSession(sourceId, session,
                            projectDirectory == null ? null : projectDirectory.toString());
                } catch (Exception e) {
                    log.debug("Failed to index session {} from {}: {}",
                            session.sessionId(), sourceId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list sessions for {}: {}", sourceId, e.getMessage());
        }

        return count;
    }

    /**
     * Index a single session's tool calls for file diffs.
     */
    public int indexSession(String source, ChatSessionSummary session) throws IOException {
        return indexSession(source, session, null);
    }

    private int indexSession(String source, ChatSessionSummary session,
                             String projectDirectoryOverride) throws IOException {
        String sessionId = session.sessionId();
        String fingerprint = source + ":" + sessionId;

        // Skip if already indexed (check by session fingerprint)
        if (entries.values().stream().anyMatch(e -> fingerprint.equals(e.getSessionFingerprint()))) {
            return 0;
        }

        ToolCallExtractor.ExtractionResult extraction = extractToolCalls(source, session);
        if (extraction == null || extraction.toolCalls().isEmpty()) {
            return 0;
        }

        int count = 0;
        String projectDir = projectDirectoryOverride;
        if (projectDir == null) {
            projectDir = extraction.projectDirectory();
            if (projectDir == null) {
                projectDir = session.workingDirectory();
            }
        }

        for (ToolCallExtractor.ExtractedToolCall call : extraction.toolCalls()) {
            if (call.isError()) continue;

            String toolName = normalizeToolName(call.toolName());
            if (!isFileEditTool(toolName)) continue;

            DiffIndexEntry entry = extractDiffEntry(call, source, sessionId, projectDir, fingerprint);
            if (entry != null) {
                entries.put(entry.getId(), entry);
                persist(entry);
                count++;
            }
        }

        return count;
    }

    private static boolean belongsToProject(ChatSourceAdapter adapter,
                                            ChatSessionSummary session,
                                            Path projectDirectory) {
        String sessionDirectory = session.workingDirectory();
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            try {
                sessionDirectory = adapter.resolveWorkingDirectory(session.sessionId())
                        .map(Path::toString).orElse(null);
            } catch (IOException ignored) {
                return false;
            }
        }
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            return false;
        }
        try {
            Path sessionPath = Path.of(sessionDirectory);
            if (!sessionPath.isAbsolute()) {
                sessionPath = projectDirectory.resolve(sessionPath);
            }
            sessionPath = sessionPath.toAbsolutePath().normalize();
            return sessionPath.equals(projectDirectory) || sessionPath.startsWith(projectDirectory);
        } catch (Exception ignored) {
            return false;
        }
    }

    private ToolCallExtractor.ExtractionResult extractToolCalls(
            String source, ChatSessionSummary session) throws IOException {
        String sessionId = session.sessionId();
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);

        return switch (source) {
            case "claude-code" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractClaude(file.get(), sessionId) : null;
            }
            case "codex" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractCodex(file.get(), sessionId) : null;
            }
            case "opencode" -> ToolCallExtractor.extractOpenCode(sessionId);
            case "qwen" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractQwen(file.get(), sessionId) : null;
            }
            case "gemini" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractGemini(file.get(), sessionId) : null;
            }
            case "cline" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractCline(file.get(), sessionId) : null;
            }
            case "cursor" -> ToolCallExtractor.extractCursor(sessionId);
            case "continue" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractContinue(file.get(), sessionId) : null;
            }
            case "aider" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractAider(file.get(), sessionId) : null;
            }
            case "pi" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractPi(file.get(), sessionId) : null;
            }
            case "kompile" -> {
                Optional<Path> file = adapter != null
                        ? ToolCallExtractor.findSessionFile(adapter, sessionId) : Optional.empty();
                yield file.isPresent() ? ToolCallExtractor.extractKompile(file.get(), sessionId) : null;
            }
            default -> null;
        };
    }

    /**
     * Extract a DiffIndexEntry from a tool call's input JSON.
     */
    private DiffIndexEntry extractDiffEntry(ToolCallExtractor.ExtractedToolCall call,
                                             String source, String sessionId,
                                             String projectDir, String fingerprint) {
        try {
            JsonNode input = mapper.readTree(call.toolInput());

            String filePath = extractFilePath(input);
            if (filePath == null || filePath.isBlank()) return null;

            String id = UUID.randomUUID().toString().substring(0, 12);
            DiffIndexEntry entry = new DiffIndexEntry();
            entry.setId(id);
            entry.setAgent(call.agentName() != null ? call.agentName() : source);
            entry.setSource(source);
            entry.setSessionId(sessionId);
            entry.setSessionFingerprint(fingerprint);
            entry.setProjectDirectory(projectDir);
            entry.setFilePath(filePath);
            entry.setToolName(call.toolName());
            entry.setTimestamp(Instant.now().toString());

            // Extract diff content based on tool type
            String toolName = normalizeToolName(call.toolName());
            switch (toolName) {
                case "edit", "str_replace_editor", "replace_in_file" -> {
                    entry.setOldString(textField(input, "old_string", "old_str", "search"));
                    entry.setNewString(textField(input, "new_string", "new_str", "replace"));
                    entry.setDiffType("edit");
                    if (entry.getOldString() != null && entry.getNewString() != null) {
                        entry.setUnifiedDiff(buildEditDiff(filePath, entry.getOldString(), entry.getNewString()));
                        entry.setLinesAdded(countDiffLines(entry.getUnifiedDiff(), "+"));
                        entry.setLinesRemoved(countDiffLines(entry.getUnifiedDiff(), "-"));
                    }
                }
                case "write", "write_to_file", "create_file" -> {
                    entry.setNewString(textField(input, "content", "file_text", "new_content"));
                    entry.setDiffType("write");
                    if (entry.getNewString() != null) {
                        entry.setLinesAdded((int) entry.getNewString().lines().count());
                    }
                }
                case "apply_diff", "patch" -> {
                    entry.setUnifiedDiff(textField(input, "diff", "patch", "unified_diff"));
                    entry.setDiffType("patch");
                    if (entry.getUnifiedDiff() != null) {
                        entry.setLinesAdded(countDiffLines(entry.getUnifiedDiff(), "+"));
                        entry.setLinesRemoved(countDiffLines(entry.getUnifiedDiff(), "-"));
                    }
                }
                default -> {
                    entry.setDiffType("other");
                    entry.setNewString(textField(input, "content", "code", "text"));
                }
            }

            return entry;
        } catch (Exception e) {
            log.debug("Failed to parse tool input for {}: {}", call.toolName(), e.getMessage());
            return null;
        }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    /**
     * Search diff entries with filters, preserving the historical newest-first default.
     */
    public List<DiffIndexEntry> search(String agent, String projectDirectory,
                                        String filePath, String contentQuery,
                                        String source, String since, String until,
                                        Integer limit) {
        return search(agent, projectDirectory, filePath, contentQuery, source,
                since, until, limit, null, null);
    }

    /**
     * Search diff entries with filters and configurable ordering.
     *
     * @param sortBy timestamp, file_path, project, agent, source, lines_added,
     *               lines_removed, or total_changes (default timestamp)
     * @param sortDir asc or desc (default desc)
     */
    public List<DiffIndexEntry> search(String agent, String projectDirectory,
                                        String filePath, String contentQuery,
                                        String source, String since, String until,
                                        Integer limit, String sortBy, String sortDir) {
        int max = limit != null && limit > 0 ? limit : 50;
        Long sinceMs = toEpochMillis(since);
        Long untilMs = toEpochMillis(until);
        Comparator<DiffIndexEntry> ordering = sortComparator(sortBy, sortDir);

        return entries.values().stream()
                .filter(e -> agent == null || agent.equals(e.getAgent()))
                .filter(e -> source == null || source.equals(e.getSource()))
                .filter(e -> projectDirectory == null || matchesProject(e, projectDirectory))
                .filter(e -> filePath == null || PathGlobMatcher.matches(e.getFilePath(), filePath))
                .filter(e -> contentQuery == null || matchesContent(e, contentQuery))
                .filter(e -> sinceMs == null || afterOrEqual(e.getTimestamp(), sinceMs))
                .filter(e -> untilMs == null || beforeOrEqual(e.getTimestamp(), untilMs))
                .sorted(ordering)
                .limit(max)
                .collect(Collectors.toList());
    }

    private static Comparator<DiffIndexEntry> sortComparator(String sortBy, String sortDir) {
        String field = normalizeSortField(sortBy);
        boolean ascending = "asc".equalsIgnoreCase(sortDir);

        Comparator<String> textValues = Comparator.nullsLast(ascending
                ? String.CASE_INSENSITIVE_ORDER
                : String.CASE_INSENSITIVE_ORDER.reversed());
        Comparator<Long> timestampValues = Comparator.nullsLast(ascending
                ? Comparator.<Long>naturalOrder()
                : Comparator.<Long>reverseOrder());

        Comparator<DiffIndexEntry> comparator = switch (field) {
            case "file_path" -> Comparator.comparing(DiffIndexEntry::getFilePath, textValues);
            case "project" -> Comparator.comparing(DiffIndexEntry::getProjectDirectory, textValues);
            case "agent" -> Comparator.comparing(DiffIndexEntry::getAgent, textValues);
            case "source" -> Comparator.comparing(DiffIndexEntry::getSource, textValues);
            case "lines_added" -> numericComparator(DiffIndexEntry::getLinesAdded, ascending);
            case "lines_removed" -> numericComparator(DiffIndexEntry::getLinesRemoved, ascending);
            case "total_changes" -> numericComparator(
                    e -> Long.sum(e.getLinesAdded(), e.getLinesRemoved()), ascending);
            default -> Comparator.comparing(
                    e -> toEpochMillis(e.getTimestamp()), timestampValues);
        };

        return comparator.thenComparing(
                DiffIndexEntry::getId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private static Comparator<DiffIndexEntry> numericComparator(
            java.util.function.ToLongFunction<DiffIndexEntry> value, boolean ascending) {
        Comparator<DiffIndexEntry> comparator = Comparator.comparingLong(value);
        return ascending ? comparator : comparator.reversed();
    }

    private static String normalizeSortField(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "timestamp";
        }
        return switch (sortBy.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "time", "date" -> "timestamp";
            case "file", "path", "filepath", "file_path" -> "file_path";
            case "project_directory", "projectdirectory", "project" -> "project";
            case "linesadded", "lines_added" -> "lines_added";
            case "linesremoved", "lines_removed" -> "lines_removed";
            case "changes", "change_size", "totalchanges", "total_changes" -> "total_changes";
            case "agent" -> "agent";
            case "source" -> "source";
            default -> "timestamp";
        };
    }

    /** Keep entries whose (best-effort parsed) timestamp is >= the bound; unparseable timestamps pass. */
    private static boolean afterOrEqual(String timestamp, long boundMs) {
        Long t = toEpochMillis(timestamp);
        return t == null || t >= boundMs;
    }

    /** Keep entries whose (best-effort parsed) timestamp is <= the bound; unparseable timestamps pass. */
    private static boolean beforeOrEqual(String timestamp, long boundMs) {
        Long t = toEpochMillis(timestamp);
        return t == null || t <= boundMs;
    }

    /**
     * Tolerant timestamp -> epoch-millis parse. Handles ISO instants ({@code ...Z}),
     * offset date-times, local date-times (e.g. an HTML {@code datetime-local} value
     * like {@code 2026-06-20T13:14}), and bare dates. Returns null if blank/unparseable
     * so the caller can treat it as "no bound".
     */
    private static Long toEpochMillis(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String s = ts.trim();
        try { return java.time.Instant.parse(s).toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.LocalDate.parse(s).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignore) { }
        return null;
    }

    // File-path matching (substring + glob) is shared with diff policy via PathGlobMatcher.

    /**
     * Get a single entry by ID.
     */
    public DiffIndexEntry get(String id) {
        return entries.get(id);
    }

    /**
     * List distinct project directories in the index.
     */
    public List<Map<String, Object>> listProjects() {
        Map<String, List<DiffIndexEntry>> byProject = entries.values().stream()
                .filter(e -> e.getProjectDirectory() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getProjectDirectory));

        return byProject.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> project = new LinkedHashMap<>();
                    project.put("projectDirectory", entry.getKey());
                    project.put("entryCount", entry.getValue().size());

                    // Derive project name from directory path
                    Path path = Paths.get(entry.getKey());
                    project.put("projectName", path.getFileName().toString());

                    // Collect agents used
                    Set<String> agents = entry.getValue().stream()
                            .map(DiffIndexEntry::getAgent)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    project.put("agents", agents);

                    // Collect sources
                    Set<String> sources = entry.getValue().stream()
                            .map(DiffIndexEntry::getSource)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    project.put("sources", sources);

                    // File count
                    long fileCount = entry.getValue().stream()
                            .map(DiffIndexEntry::getFilePath)
                            .distinct().count();
                    project.put("fileCount", fileCount);

                    // Total lines
                    long totalAdded = entry.getValue().stream().mapToLong(DiffIndexEntry::getLinesAdded).sum();
                    long totalRemoved = entry.getValue().stream().mapToLong(DiffIndexEntry::getLinesRemoved).sum();
                    project.put("totalLinesAdded", totalAdded);
                    project.put("totalLinesRemoved", totalRemoved);

                    return project;
                })
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(m -> (Integer) m.get("entryCount")).reversed())
                .collect(Collectors.toList());
    }

    /**
     * List distinct agents in the index.
     */
    public List<Map<String, Object>> listAgents() {
        Map<String, List<DiffIndexEntry>> byAgent = entries.values().stream()
                .filter(e -> e.getAgent() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getAgent));

        return byAgent.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> agent = new LinkedHashMap<>();
                    agent.put("agent", entry.getKey());
                    agent.put("entryCount", entry.getValue().size());

                    long fileCount = entry.getValue().stream()
                            .map(DiffIndexEntry::getFilePath)
                            .distinct().count();
                    agent.put("fileCount", fileCount);

                    Set<String> projects = entry.getValue().stream()
                            .map(DiffIndexEntry::getProjectDirectory)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    agent.put("projects", projects);
                    agent.put("projectCount", projects.size());

                    long totalAdded = entry.getValue().stream().mapToLong(DiffIndexEntry::getLinesAdded).sum();
                    long totalRemoved = entry.getValue().stream().mapToLong(DiffIndexEntry::getLinesRemoved).sum();
                    agent.put("totalLinesAdded", totalAdded);
                    agent.put("totalLinesRemoved", totalRemoved);

                    return agent;
                })
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(m -> (Integer) m.get("entryCount")).reversed())
                .collect(Collectors.toList());
    }

    /**
     * List distinct transcript sessions with per-session aggregates, most recently
     * active first. Lets the UI browse the mined diffs grouped by session, across all
     * agents.
     */
    public List<Map<String, Object>> listSessions() {
        Map<String, List<DiffIndexEntry>> bySession = entries.values().stream()
                .filter(e -> e.getSessionId() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getSessionId));

        return bySession.entrySet().stream()
                .map(entry -> {
                    List<DiffIndexEntry> es = entry.getValue();
                    Map<String, Object> session = new LinkedHashMap<>();
                    session.put("sessionId", entry.getKey());

                    es.stream().map(DiffIndexEntry::getSessionFingerprint)
                            .filter(Objects::nonNull).findFirst()
                            .ifPresent(fp -> session.put("sessionFingerprint", fp));

                    session.put("entryCount", es.size());

                    Set<String> agents = es.stream().map(DiffIndexEntry::getAgent)
                            .filter(Objects::nonNull).collect(Collectors.toSet());
                    session.put("agents", agents);

                    Set<String> sources = es.stream().map(DiffIndexEntry::getSource)
                            .filter(Objects::nonNull).collect(Collectors.toSet());
                    session.put("sources", sources);

                    Set<String> projects = es.stream().map(DiffIndexEntry::getProjectDirectory)
                            .filter(Objects::nonNull).collect(Collectors.toSet());
                    session.put("projects", projects);

                    long fileCount = es.stream().map(DiffIndexEntry::getFilePath)
                            .filter(Objects::nonNull).distinct().count();
                    session.put("fileCount", fileCount);

                    session.put("totalLinesAdded", es.stream().mapToLong(DiffIndexEntry::getLinesAdded).sum());
                    session.put("totalLinesRemoved", es.stream().mapToLong(DiffIndexEntry::getLinesRemoved).sum());

                    String first = es.stream().map(DiffIndexEntry::getTimestamp)
                            .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
                    String last = es.stream().map(DiffIndexEntry::getTimestamp)
                            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                    session.put("firstTimestamp", first);
                    session.put("lastTimestamp", last);

                    // Primary agent = whoever produced the most recent edit in the session.
                    String primaryAgent = es.stream()
                            .max(Comparator.comparing(e -> e.getTimestamp() == null ? "" : e.getTimestamp()))
                            .map(DiffIndexEntry::getAgent).orElse(null);
                    session.put("agent", primaryAgent);

                    return session;
                })
                .sorted(Comparator.comparing((Map<String, Object> m) -> {
                    Object ts = m.get("lastTimestamp");
                    return ts == null ? "" : ts.toString();
                }).reversed())
                .collect(Collectors.toList());
    }

    /**
     * All diff entries belonging to a single transcript session, newest first.
     */
    public List<DiffIndexEntry> sessionEntries(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return entries.values().stream()
                .filter(e -> sessionId.equals(e.getSessionId()))
                .sorted(Comparator.comparing((DiffIndexEntry e) -> e.getTimestamp() == null ? "" : e.getTimestamp()).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get index statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalEntries", entries.size());
        stats.put("indexing", indexing.get());

        long totalAdded = entries.values().stream().mapToLong(DiffIndexEntry::getLinesAdded).sum();
        long totalRemoved = entries.values().stream().mapToLong(DiffIndexEntry::getLinesRemoved).sum();
        stats.put("totalLinesAdded", totalAdded);
        stats.put("totalLinesRemoved", totalRemoved);

        long fileCount = entries.values().stream()
                .map(DiffIndexEntry::getFilePath).distinct().count();
        stats.put("uniqueFiles", fileCount);

        long projectCount = entries.values().stream()
                .map(DiffIndexEntry::getProjectDirectory)
                .filter(Objects::nonNull).distinct().count();
        stats.put("uniqueProjects", projectCount);

        Map<String, Long> byAgent = entries.values().stream()
                .filter(e -> e.getAgent() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getAgent, Collectors.counting()));
        stats.put("byAgent", byAgent);

        Map<String, Long> bySource = entries.values().stream()
                .filter(e -> e.getSource() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getSource, Collectors.counting()));
        stats.put("bySource", bySource);

        Map<String, Long> byDiffType = entries.values().stream()
                .filter(e -> e.getDiffType() != null)
                .collect(Collectors.groupingBy(DiffIndexEntry::getDiffType, Collectors.counting()));
        stats.put("byDiffType", byDiffType);

        return stats;
    }

    public boolean isIndexing() {
        return indexing.get();
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void persist(DiffIndexEntry entry) {
        try {
            Path file = indexDir.resolve(entry.getId() + ".json");
            mapper.writeValue(file.toFile(), entry);
        } catch (IOException e) {
            log.error("Failed to persist diff index entry {}: {}", entry.getId(), e.getMessage());
        }
    }

    private void loadExisting() {
        try (Stream<Path> files = Files.list(indexDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            DiffIndexEntry entry = mapper.readValue(p.toFile(), DiffIndexEntry.class);
                            if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                                log.warn("Skipping diff index entry without an id: {}", p);
                                return;
                            }
                            entries.put(entry.getId(), entry);
                            // Track id counter
                            long numericPart = 0;
                            try {
                                numericPart = Long.parseLong(entry.getId().replaceAll("[^0-9]", ""), 16);
                            } catch (NumberFormatException e) {
                                log.trace("Could not parse numeric part of diff index entry id '{}': {}", entry.getId(), e.getMessage());
                            }
                            if (numericPart > idCounter.get()) {
                                idCounter.set(numericPart);
                            }
                        } catch (IOException e) {
                            log.debug("Failed to load index entry from {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("No existing diff index entries found: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isFileEditTool(String toolName) {
        return EDIT_TOOL_NAMES.contains(toolName);
    }

    private String normalizeToolName(String toolName) {
        if (toolName == null) return "";
        // Strip MCP prefixes like "mcp__kompile__edit"
        if (toolName.contains("__")) {
            String[] parts = toolName.split("__");
            return parts[parts.length - 1].toLowerCase();
        }
        return toolName.toLowerCase();
    }

    private String extractFilePath(JsonNode input) {
        for (String field : new String[]{"file_path", "filePath", "path", "file", "filename", "target_file"}) {
            JsonNode node = input.path(field);
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private String textField(JsonNode input, String... candidates) {
        for (String field : candidates) {
            JsonNode node = input.path(field);
            if (node.isTextual()) return node.asText();
        }
        return null;
    }

    private boolean matchesProject(DiffIndexEntry entry, String projectDir) {
        if (entry.getProjectDirectory() == null) return false;
        // Match by exact path or by project directory name
        return entry.getProjectDirectory().equals(projectDir)
                || entry.getProjectDirectory().endsWith("/" + projectDir)
                || entry.getProjectDirectory().contains(projectDir);
    }

    private boolean matchesContent(DiffIndexEntry entry, String query) {
        String q = query.toLowerCase();
        if (entry.getFilePath() != null && entry.getFilePath().toLowerCase().contains(q)) return true;
        if (entry.getOldString() != null && entry.getOldString().toLowerCase().contains(q)) return true;
        if (entry.getNewString() != null && entry.getNewString().toLowerCase().contains(q)) return true;
        if (entry.getUnifiedDiff() != null && entry.getUnifiedDiff().toLowerCase().contains(q)) return true;
        return false;
    }

    private String buildEditDiff(String filePath, String oldStr, String newStr) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ b/").append(filePath).append("\n");
        String[] oldLines = oldStr.split("\n", -1);
        String[] newLines = newStr.split("\n", -1);
        sb.append("@@ -1,").append(oldLines.length).append(" +1,").append(newLines.length).append(" @@\n");
        for (String line : oldLines) {
            sb.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    private int countDiffLines(String diff, String prefix) {
        if (diff == null) return 0;
        return (int) diff.lines()
                .filter(line -> line.startsWith(prefix) && !line.startsWith(prefix + prefix))
                .count();
    }

}
