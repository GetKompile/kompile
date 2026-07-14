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

package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CodexAdapter implements ChatSourceAdapter {

    public static final String ID = "codex";

    private static final Pattern ROLLOUT_NAME = Pattern.compile(
            "^rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}(?:-\\d{2}){2}-(.+)\\.jsonl(?:\\.zst)?$");
    private static final Pattern STATE_DATABASE_NAME = Pattern.compile("^state_([0-9]+)\\.sqlite$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenAI Codex";
    }

    protected Path codexHome() {
        return ChatAdapterSupport.userHome().resolve(".codex");
    }

    protected Path sessionsDir() {
        return codexHome().resolve("sessions");
    }

    protected Path historyFile() {
        return codexHome().resolve("history.jsonl");
    }

    protected Optional<Path> stateDatabase() {
        String configured = System.getenv("CODEX_SQLITE_HOME");
        if (configured != null && !configured.isBlank()) {
            Optional<Path> database = findStateDatabase(Path.of(configured));
            if (database.isPresent()) {
                return database;
            }
        }
        return findStateDatabase(codexHome());
    }

    private static Optional<Path> findStateDatabase(Path root) {
        if (Files.isRegularFile(root)
                && STATE_DATABASE_NAME.matcher(root.getFileName().toString()).matches()) {
            return Optional.of(root);
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> STATE_DATABASE_NAME.matcher(path.getFileName().toString()).matches())
                    .max(Comparator.comparingInt(CodexAdapter::stateDatabaseVersion));
        } catch (IOException ignore) {
            return Optional.empty();
        }
    }

    private static int stateDatabaseVersion(Path path) {
        Matcher matcher = STATE_DATABASE_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignore) {
            return -1;
        }
    }

    @Override
    public SourceInfo discover() {
        boolean available = Files.isDirectory(sessionsDir())
                || Files.isRegularFile(historyFile())
                || stateDatabase().isPresent();
        if (!available) {
            return SourceInfo.unavailable(id(), displayName(), codexHome().toString(), "not found");
        }
        try {
            return SourceInfo.available(id(), displayName(), codexHome().toString(), list().size());
        } catch (IOException e) {
            return SourceInfo.unavailable(
                    id(), displayName(), codexHome().toString(), "sessions unreadable: " + e.getMessage());
        }
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        return listInternal(null);
    }

    @Override
    public List<ChatSessionSummary> list(Path workingDirectory) throws IOException {
        return listInternal(workingDirectory == null
                ? null
                : workingDirectory.toAbsolutePath().normalize());
    }

    private List<ChatSessionSummary> listInternal(Path workingDirectory) throws IOException {
        Optional<List<ChatSessionSummary>> indexed = listIndexedThreads(workingDirectory);
        if (indexed.isPresent()) {
            return indexed.get();
        }

        // history.jsonl has no working-directory field. For project-scoped resume loading,
        // rollout metadata already contains the native picker title and cwd, so avoid parsing
        // the entire (often tens-of-megabytes) history file.
        Map<String, HistorySummary> history = workingDirectory == null
                && Files.isRegularFile(historyFile())
                ? readHistorySummaries(historyFile())
                : Collections.emptyMap();
        Map<String, ChatSessionSummary> summaries = new LinkedHashMap<>();
        Set<String> rolloutSessionIds = new HashSet<>();

        for (Path path : rolloutFilesNewestFirst()) {
            RolloutMeta meta;
            try {
                meta = readRolloutMeta(path);
            } catch (IOException ignore) {
                continue;
            }
            String sessionId = meta.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            rolloutSessionIds.add(sessionId);
            if (!meta.interactive()
                    || (workingDirectory != null
                    && !workingDirectoryMatches(meta.workingDirectory(), workingDirectory))) {
                continue;
            }
            HistorySummary historySummary = history.get(sessionId);
            String title = firstNonBlank(
                    historySummary == null ? null : historySummary.title(),
                    meta.title(),
                    "(untitled)");
            summaries.putIfAbsent(sessionId, new ChatSessionSummary(
                    sessionId,
                    id(),
                    title,
                    id(),
                    meta.turnCount(),
                    ChatAdapterSupport.lastModified(path),
                    meta.workingDirectory()));
        }

        // Older Codex installations may only have history.jsonl. Preserve those sessions for
        // global listings. They cannot be attributed safely to a project-scoped listing.
        if (workingDirectory == null && Files.isRegularFile(historyFile())) {
            long modified = ChatAdapterSupport.lastModified(historyFile());
            for (Map.Entry<String, HistorySummary> entry : history.entrySet()) {
                if (rolloutSessionIds.contains(entry.getKey())) {
                    continue;
                }
                String title = firstNonBlank(entry.getValue().title(), "(untitled)");
                summaries.putIfAbsent(entry.getKey(), new ChatSessionSummary(
                        entry.getKey(),
                        id(),
                        title,
                        id(),
                        entry.getValue().turnCount(),
                        modified));
            }
        }

        List<ChatSessionSummary> result = new ArrayList<>(summaries.values());
        result.sort(Comparator.comparingLong(ChatSessionSummary::lastModifiedMillis).reversed());
        return result;
    }

    private Optional<List<ChatSessionSummary>> listIndexedThreads(Path workingDirectory) {
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return Optional.empty();
        }
        Optional<Path> database = stateDatabase();
        if (database.isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT t.id, t.title, t.first_user_message, t.preview, t.cwd, "
                + "t.source, t.thread_source, "
                + "COALESCE(NULLIF(t.updated_at_ms, 0), t.updated_at * 1000, "
                + "NULLIF(t.created_at_ms, 0), t.created_at * 1000, 0) AS modified "
                + "FROM threads t "
                + "WHERE COALESCE(t.archived, 0) = 0 "
                + "AND NOT EXISTS (SELECT 1 FROM thread_spawn_edges e "
                + "WHERE e.child_thread_id = t.id) "
                + "AND (? IS NULL OR t.cwd = ?) "
                + "ORDER BY modified DESC, t.id DESC";
        try (Connection connection = openStateDatabase(database.get());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (workingDirectory == null) {
                statement.setNull(1, Types.VARCHAR);
                statement.setNull(2, Types.VARCHAR);
            } else {
                String directory = workingDirectory.toString();
                statement.setString(1, directory);
                statement.setString(2, directory);
            }

            List<ChatSessionSummary> summaries = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!isInteractive(
                            rows.getString("source"),
                            null,
                            rows.getString("thread_source"))) {
                        continue;
                    }
                    String sessionId = rows.getString("id");
                    if (sessionId == null || sessionId.isBlank()) {
                        continue;
                    }
                    String title = nativePickerTitle(
                            rows.getString("title"),
                            rows.getString("first_user_message"),
                            rows.getString("preview"));
                    summaries.add(new ChatSessionSummary(
                            sessionId,
                            id(),
                            title,
                            id(),
                            -1,
                            rows.getLong("modified"),
                            rows.getString("cwd")));
                }
            }
            return Optional.of(summaries);
        } catch (SQLException | RuntimeException ignore) {
            // Older Codex versions may not have the indexed thread schema. Fall back to rollouts.
            return Optional.empty();
        }
    }

    @Override
    public List<ChatSessionSummary> list(int limit) throws IOException {
        if (limit == 0) {
            return Collections.emptyList();
        }
        List<ChatSessionSummary> sessions = list();
        if (limit < 0 || sessions.size() <= limit) {
            return sessions;
        }
        return new ArrayList<>(sessions.subList(0, limit));
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> rollout = findRollout(sessionId);
        if (rollout.isPresent()) {
            return parseRollout(rollout.get());
        }
        if (Files.isRegularFile(historyFile())) {
            return readHistoryGrouped(historyFile()).getOrDefault(sessionId, Collections.emptyList());
        }
        return Collections.emptyList();
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> rollout = findRollout(sessionId);
        if (rollout.isEmpty()) {
            return Optional.empty();
        }
        String directory = readRolloutMeta(rollout.get()).workingDirectory();
        return directory == null || directory.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(directory));
    }

    @Override
    public String resolveTitle(String sessionId) throws IOException {
        for (ChatSessionSummary summary : list()) {
            if (sessionId.equals(summary.sessionId())) {
                return summary.title();
            }
        }
        return sessionId;
    }

    protected Optional<Path> findRollout(String sessionId) throws IOException {
        Optional<Path> indexed = findIndexedRollout(sessionId);
        if (indexed.isPresent()) {
            return indexed;
        }

        Path sessions = sessionsDir();
        if (!Files.isDirectory(sessions)) {
            return Optional.empty();
        }

        Path best = null;
        long bestModified = Long.MIN_VALUE;
        for (Path path : rolloutFilesNewestFirst()) {
            String fileName = path.getFileName().toString();
            boolean matches = fileName.equals(sessionId)
                    || fileName.equals(sessionId + ".jsonl")
                    || fileName.equals(sessionId + ".jsonl.zst")
                    || sessionId.equals(extractIdFromRollout(fileName));
            if (!matches) {
                try {
                    matches = sessionId.equals(readRolloutMeta(path).sessionId());
                } catch (IOException ignore) {
                }
            }
            if (matches) {
                long modified = ChatAdapterSupport.lastModified(path);
                if (best == null || modified > bestModified) {
                    best = path;
                    bestModified = modified;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Path> findIndexedRollout(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !ChatAdapterSupport.sqliteAvailable()) {
            return Optional.empty();
        }
        Optional<Path> database = stateDatabase();
        if (database.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = openStateDatabase(database.get());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT rollout_path FROM threads WHERE id = ? LIMIT 1")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String stored = rows.getString(1);
                if (stored == null || stored.isBlank()) {
                    return Optional.empty();
                }
                Path path = Path.of(stored);
                if (!path.isAbsolute()) {
                    path = codexHome().resolve(path).normalize();
                }
                return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
            }
        } catch (SQLException | RuntimeException ignore) {
            return Optional.empty();
        }
    }

    protected static String extractIdFromRollout(String fileName) {
        Matcher matcher = ROLLOUT_NAME.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : null;
    }

    protected static List<ChatTurn> parseRollout(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = rolloutReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    if (!"response_item".equalsIgnoreCase(node.path("type").asText(""))) {
                        continue;
                    }
                    JsonNode payload = node.path("payload");
                    String role = ChatAdapterSupport.extractRole(payload);
                    String content = ChatAdapterSupport.extractContent(payload);
                    if (role != null && content != null && !content.isBlank()) {
                        out.add(new ChatTurn(role, content));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private List<Path> rolloutFilesNewestFirst() throws IOException {
        Path sessions = sessionsDir();
        if (!Files.isDirectory(sessions)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.walk(sessions)) {
            return stream.filter(Files::isRegularFile)
                    .filter(CodexAdapter::isRolloutFile)
                    .map(path -> new RolloutFile(
                            path, ChatAdapterSupport.lastModified(path)))
                    .sorted(Comparator.comparingLong(
                            RolloutFile::modifiedMillis).reversed())
                    .map(RolloutFile::path)
                    .toList();
        }
    }

    private static boolean isRolloutFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsonl") || name.endsWith(".jsonl.zst");
    }

    private static RolloutMeta readRolloutMeta(Path file) throws IOException {
        String sessionId = extractIdFromRollout(file.getFileName().toString());
        String source = null;
        String originator = null;
        String threadSource = null;
        String workingDirectory = null;
        String title = null;
        int turnCount = -1;

        // The session_meta and initial prompt are at the beginning of Codex rollouts. Do not
        // scan multi-megabyte transcripts just to populate a resume picker row.
        try (BufferedReader reader = rolloutReader(file)) {
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead++ < 200) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    if ("session_meta".equalsIgnoreCase(type)) {
                        JsonNode payload = node.path("payload");
                        sessionId = firstNonBlank(
                                payload.path("id").asText(null),
                                payload.path("session_id").asText(null),
                                sessionId);
                        source = nodeFingerprint(payload.get("source"));
                        originator = nodeFingerprint(payload.get("originator"));
                        threadSource = nodeFingerprint(payload.get("thread_source"));
                        workingDirectory = payload.path("cwd").asText(null);
                        if (!isInteractive(source, originator, threadSource)) {
                            return new RolloutMeta(
                                    sessionId, null, workingDirectory, turnCount, false);
                        }
                        continue;
                    }
                    if (!"response_item".equalsIgnoreCase(type)) {
                        continue;
                    }

                    JsonNode payload = node.path("payload");
                    String role = ChatAdapterSupport.extractRole(payload);
                    String content = ChatAdapterSupport.extractContent(payload);
                    if (role == null || content == null || content.isBlank()) {
                        continue;
                    }
                    if (title == null && "user".equalsIgnoreCase(role)) {
                        title = meaningfulTitle(content);
                        if (title != null && sessionId != null && !sessionId.isBlank()) {
                            break;
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        }

        return new RolloutMeta(
                sessionId,
                title,
                workingDirectory,
                turnCount,
                isInteractive(source, originator, threadSource));
    }

    private static boolean isInteractive(
            String source,
            String originator,
            String threadSource) {
        String fingerprint = String.join(" ",
                firstNonBlank(source),
                firstNonBlank(originator),
                firstNonBlank(threadSource)).toLowerCase(Locale.ROOT);
        if (fingerprint.contains("sub_agent")
                || fingerprint.contains("subagent")
                || fingerprint.contains("sub-agent")) {
            return false;
        }

        String sourceKind = normalizeSourceKind(source);
        if (!sourceKind.isEmpty()) {
            return "cli".equals(sourceKind)
                    || "vscode".equals(sourceKind)
                    || "vs_code".equals(sourceKind);
        }

        // Rollouts from older Codex releases may not record SessionSource. Keep those legacy
        // interactive sessions, but still reject known non-picker entry points.
        return !fingerprint.contains("codex_exec")
                && !fingerprint.matches(".*(?:^|[^a-z])exec(?:[^a-z]|$).*")
                && !fingerprint.contains("mcp")
                && !fingerprint.contains("app_server")
                && !fingerprint.contains("appserver")
                && !fingerprint.contains("app-server");
    }

    private static String normalizeSourceKind(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String nodeFingerprint(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText("") : node.toString();
    }

    private static String nativePickerTitle(
            String storedTitle,
            String firstUserMessage,
            String preview) {
        String title = trimmed(storedTitle);
        String firstUser = trimmed(firstUserMessage);
        String pickerPreview = trimmed(preview);
        if (pickerPreview.isEmpty()) {
            pickerPreview = firstUser;
        }

        // Codex exposes a thread name only when the stored title differs from both the first
        // user message and the preview. The picker otherwise displays the preview verbatim.
        if (!title.isEmpty()
                && !title.equals(firstUser)
                && !title.equals(pickerPreview)) {
            return compactPickerText(title);
        }
        return pickerPreview.isEmpty()
                ? "(no message yet)"
                : compactPickerText(pickerPreview);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String compactPickerText(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String meaningfulTitle(String content) {
        String candidate = content == null ? "" : content.trim();
        candidate = stripLeadingBlock(candidate, "<environment_context>", "</environment_context>");
        candidate = stripLeadingBlock(candidate, "<permissions instructions>", "</permissions instructions>");
        if (candidate.isBlank()
                || candidate.startsWith("# AGENTS.md instructions")
                || candidate.startsWith("<INSTRUCTIONS>")
                || candidate.startsWith("<collaboration_mode>")) {
            return null;
        }

        return candidate.replaceAll("\\s+", " ").trim();
    }

    private static String stripLeadingBlock(String value, String opening, String closing) {
        String candidate = value;
        while (candidate.startsWith(opening)) {
            int end = candidate.indexOf(closing);
            if (end < 0) {
                return "";
            }
            candidate = candidate.substring(end + closing.length()).trim();
        }
        return candidate;
    }

    private static BufferedReader rolloutReader(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream input = Files.newInputStream(file);
        if (name.endsWith(".zst")) {
            input = openZstdStream(input);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static Connection openStateDatabase(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + database.toAbsolutePath() + "?mode=ro");
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 1000");
            statement.execute("PRAGMA query_only = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private static InputStream openZstdStream(InputStream raw) throws IOException {
        try {
            Class<?> type = Class.forName("com.github.luben.zstd.ZstdInputStream");
            return (InputStream) type.getConstructor(InputStream.class).newInstance(raw);
        } catch (Throwable t) {
            try {
                raw.close();
            } catch (IOException ignore) {
            }
            throw new IOException("zstd-jni unavailable; cannot read .zst rollout: " + t.getMessage(), t);
        }
    }

    private static Map<String, HistorySummary> readHistorySummaries(Path file) throws IOException {
        Map<String, HistorySummary> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String sessionId = node.path("session_id").asText(null);
                    String text = node.path("text").asText(null);
                    if (sessionId == null || text == null) {
                        continue;
                    }
                    HistorySummary current = out.get(sessionId);
                    String title = current == null ? null : current.title();
                    if (title == null || title.isBlank()) {
                        title = meaningfulTitle(text);
                    }
                    out.put(sessionId, new HistorySummary(
                            title,
                            current == null ? 1 : current.turnCount() + 1));
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private static Map<String, List<ChatTurn>> readHistoryGrouped(Path file) throws IOException {
        Map<String, List<ChatTurn>> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String sessionId = node.path("session_id").asText(null);
                    String text = node.path("text").asText(null);
                    if (sessionId == null || text == null) {
                        continue;
                    }
                    out.computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                            .add(new ChatTurn("user", text));
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private static boolean workingDirectoryMatches(
            String sessionDirectory,
            Path workingDirectory) {
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            return false;
        }
        String session = sessionDirectory.replace('\\', '/');
        String requested = workingDirectory.toAbsolutePath().normalize().toString()
                .replace('\\', '/');
        return requested.equals(session);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record HistorySummary(String title, int turnCount) {
    }

    private record RolloutFile(Path path, long modifiedMillis) {
    }

    private record RolloutMeta(
            String sessionId,
            String title,
            String workingDirectory,
            int turnCount,
            boolean interactive) {
    }
}
