package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import org.sqlite.Collation;
import org.sqlite.Function;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Disposable projection of conversation .txt and tool-call JSONL sources. Never owns history.
 * Connections are operation-scoped; DELETE journaling avoids persistent WAL/checkpoint state.
 * A committed source checkpoint and its rows always advance in the same transaction.
 */
public final class ConversationSearchIndex {
    private static final int CHUNK_CHARS = 4096;
    private static final String COMBINED = "tool-calls/all-tool-calls.jsonl";
    private final Path directory;
    private final Path database;

    public ConversationSearchIndex(Path conversationsDirectory) {
        directory = conversationsDirectory.toAbsolutePath().normalize();
        database = directory.resolve(".search-index.db");
    }

    public record Hit(String sessionId, String agent, String project, String started,
                      int line, int chunk, String snippet, double score) { }
    public record Page(List<Hit> hits, int offset, Integer nextOffset) { }
    private record Source(long size, long mtime, long checkpoint, String tail) { }

    private Connection open(boolean readOnly) throws SQLException, IOException {
        safePath(directory); safePath(database); safePath(database.resolveSibling(database.getFileName() + "-journal"));
        if (!readOnly) Files.createDirectories(directory);
        Connection c = DriverManager.getConnection(readOnly
                ? "jdbc:sqlite:" + database.toUri() + "?mode=ro" : "jdbc:sqlite:" + database);
        try {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                if (readOnly) s.execute("PRAGMA query_only=ON");
            }
            // SQLite NOCASE/lower are ASCII-only. Preserve the catalog's Java Unicode semantics.
            Collation.create(c, "JAVA_NOCASE", new Collation() {
                @Override protected int xCompare(String a, String b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a, b);
                }
            });
            Collation.create(c, "JAVA_BINARY", new Collation() {
                @Override protected int xCompare(String a, String b) { return a.compareTo(b); }
            });
            Function.create(c, "java_lower", new Function() {
                @Override protected void xFunc() throws SQLException {
                    String text = value_text(0);
                    if (text == null) result(); else result(text.toLowerCase());
                }
            });
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private void schema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            try (ResultSet version = s.executeQuery("PRAGMA user_version")) {
                int current = version.next() ? version.getInt(1) : 0;
                if (current == 1) {
                    s.execute("CREATE TABLE IF NOT EXISTS retired_sessions(session TEXT PRIMARY KEY)");
                    return;
                }
                if (current != 0) throw new SQLException("Unsupported conversation search index schema");
            }
            s.execute("CREATE TABLE IF NOT EXISTS retired_sessions(session TEXT PRIMARY KEY)");
            s.execute("PRAGMA journal_mode=DELETE");
            s.execute("CREATE TABLE IF NOT EXISTS search_sources (path TEXT PRIMARY KEY, kind TEXT NOT NULL, " +
                    "size INTEGER NOT NULL, mtime INTEGER NOT NULL, checkpoint INTEGER NOT NULL, tail TEXT NOT NULL, " +
                    "session TEXT, agent TEXT, project TEXT, started TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS transcript_chunks (id INTEGER PRIMARY KEY, source TEXT NOT NULL, " +
                    "line INTEGER NOT NULL, part INTEGER NOT NULL, text TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS transcript_source ON transcript_chunks(source)");
            s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(text, " +
                    "content='transcript_chunks', content_rowid='id')");
            s.execute("CREATE TRIGGER IF NOT EXISTS transcript_insert AFTER INSERT ON transcript_chunks BEGIN " +
                    "INSERT INTO transcript_fts(rowid,text) VALUES(new.id,new.text); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS transcript_delete AFTER DELETE ON transcript_chunks BEGIN " +
                    "INSERT INTO transcript_fts(transcript_fts,rowid,text) VALUES('delete',old.id,old.text); END");
            s.execute("CREATE TABLE IF NOT EXISTS catalog_calls (source TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "json TEXT NOT NULL, tool TEXT, summary TEXT, input TEXT, session TEXT, category TEXT, agent TEXT, " +
                    "project TEXT, timestamp TEXT, origin TEXT, error INTEGER NOT NULL, " +
                    "PRIMARY KEY(source,ordinal))");
            s.execute("CREATE INDEX IF NOT EXISTS catalog_time ON catalog_calls(source,timestamp)");
            s.execute("PRAGMA user_version=1");
        }
    }

    /** Explicit rebuild of projection tables only. Source files are never changed or removed. */
    public void rebuild() throws SQLException, IOException {
        try (Connection c = open(false)) {
            schema(c);
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM transcript_chunks");
                s.executeUpdate("DELETE FROM catalog_calls");
                s.executeUpdate("DELETE FROM search_sources");
                // Rebuild also repairs FTS shadow state from its now-empty content table.
                s.executeUpdate("INSERT INTO transcript_fts(transcript_fts) VALUES('rebuild')");
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
        syncTranscripts();
        syncToolCalls();
    }

    private static void safePath(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symlinked conversation index path: " + current);
    }

    private void syncRetired(Connection c) throws SQLException, IOException {
        Path retired = directory.resolve(".retired"); safePath(retired);
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM retired_sessions");
            if (Files.isDirectory(retired, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> files = Files.list(retired);
                     PreparedStatement insert = c.prepareStatement("INSERT INTO retired_sessions(session) VALUES(?)")) {
                    for (Path file : files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                        insert.setString(1, file.getFileName().toString()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            c.commit();
        } catch (SQLException | IOException failure) { c.rollback(); throw failure; }
        finally { c.setAutoCommit(true); }
    }

    public void syncTranscripts() throws SQLException, IOException { sync("transcript"); }
    public void syncToolCalls() throws SQLException, IOException { sync("catalog"); }

    private void sync(String kind) throws SQLException, IOException {
        Path root = kind.equals("transcript") ? directory : directory.resolve("tool-calls");
        safePath(root);
        List<Path> sources;
        if (!Files.exists(root)) sources = List.of();
        else try (Stream<Path> files = Files.list(root)) {
            sources = files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(kind.equals("transcript") ? ".txt" : ".jsonl"))
                    // Usage is a separate authoritative ledger, not a catalog record stream.
                    .filter(p -> !kind.equals("catalog") || !Set.of("tool-usage.jsonl", "tool-call-usage.jsonl")
                            .contains(p.getFileName().toString()))
                    .sorted().toList();
        }
        Set<String> present = new HashSet<>();
        try (Connection c = open(false)) {
            schema(c);
            syncRetired(c);
            for (Path file : sources) {
                String key = directory.relativize(file).toString().replace('\\', '/');
                present.add(key);
                long size = Files.size(file);
                long mtime = Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);
                Source snapshot = source(c, key);
                if (snapshot != null && snapshot.size == size && snapshot.mtime == mtime) continue;
                // Reserve the writer before reading its checkpoint, including across JVMs.
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement lock = c.prepareStatement("UPDATE search_sources SET size=size WHERE path=?")) {
                        lock.setString(1, key); lock.executeUpdate();
                    }
                    Source previous = source(c, key);
                    if (previous == null || previous.size != size || previous.mtime != mtime) {
                        if (kind.equals("transcript")) indexTranscript(c, file, key, size, mtime);
                        else indexCatalog(c, file, key, size, mtime, previous);
                    }
                    c.commit();
                } catch (SQLException | IOException | RuntimeException e) {
                    c.rollback(); throw e;
                } finally { c.setAutoCommit(true); }
            }
            // Reconcile deletions even when all remaining files are unchanged.
            c.setAutoCommit(false);
            try {
                try (Statement lock = c.createStatement()) { lock.executeUpdate("UPDATE search_sources SET size=size WHERE 0"); }
                List<String> removed = new ArrayList<>();
                try (PreparedStatement p = c.prepareStatement("SELECT path FROM search_sources WHERE kind=?")) {
                    p.setString(1, kind);
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) if (!present.contains(r.getString(1))) removed.add(r.getString(1));
                    }
                }
                for (String key : removed) if (!Files.isRegularFile(directory.resolve(key),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) removeSource(c, key);
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

    private Source source(Connection c, String key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT size,mtime,checkpoint,tail FROM search_sources WHERE path=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new Source(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4)) : null;
            }
        }
    }

    private void removeSource(Connection c, String key) throws SQLException {
        for (String table : List.of("transcript_chunks", "catalog_calls", "search_sources")) {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE " +
                    (table.equals("search_sources") ? "path" : "source") + "=?")) {
                p.setString(1, key); p.executeUpdate();
            }
        }
    }

    private void saveSource(Connection c, String key, String kind, long size, long mtime,
                            long checkpoint, String tail, String session, String agent,
                            String project, String started) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO search_sources " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            Object[] values = {key, kind, size, mtime, checkpoint, tail, session, agent, project, started};
            bind(p, Arrays.asList(values)); p.executeUpdate();
        }
    }

    private void indexTranscript(Connection c, Path file, String key, long size, long mtime)
            throws IOException, SQLException {
        removeSource(c, key);
        String session = file.getFileName().toString().replaceFirst("\\.txt$", "");
        String agent = "", project = "", started = "";
        boolean header = true;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             PreparedStatement p = c.prepareStatement("INSERT INTO transcript_chunks(source,line,part,text) VALUES(?,?,?,?)")) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.startsWith("[resumed ")) header = true;
                if (header) {
                    if (line.startsWith("Agent:")) agent = line.substring(6).trim();
                    if (line.startsWith("CWD:")) project = line.substring(4).trim();
                    if (line.startsWith("Started:")) started = line.substring(8).trim();
                    if (line.startsWith("> ") || line.startsWith("< ") || line.startsWith("[agent:")) header = false;
                }
                // Overlap keeps ordinary words/short phrases spanning a chunk boundary searchable.
                for (int start = 0, part = 0; start < line.length(); part++) {
                    int end = Math.min(line.length(), start + CHUNK_CHARS);
                    if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) end--;
                    p.setString(1, key); p.setInt(2, number); p.setInt(3, part);
                    p.setString(4, line.substring(start, end)); p.executeUpdate();
                    if (end == line.length()) break;
                    start = end - 128;
                    if (Character.isLowSurrogate(line.charAt(start))) start++;
                }
            }
        }
        saveSource(c, key, "transcript", size, mtime, 0, "", session, agent, project, started);
    }

    /** Append guard: file identity plus head/tail samples. Explicit rebuild covers arbitrary middle rewrites. */
    private String tail(Path file, long offset) throws IOException {
        int bytes = (int) Math.min(256, offset);
        String identity = String.valueOf(Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        try (FileChannel channel = FileChannel.open(file)) {
            var head = java.nio.ByteBuffer.allocate(bytes);
            while (head.hasRemaining() && channel.read(head) > 0) { }
            channel.position(offset - bytes);
            var buffer = java.nio.ByteBuffer.allocate(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            return identity + ":" + Base64.getEncoder().encodeToString(head.array()) + ":" +
                    Base64.getEncoder().encodeToString(buffer.array());
        }
    }

    private void indexCatalog(Connection c, Path file, String key, long size, long mtime, Source previous)
            throws SQLException, IOException {
        long checkpoint = 0;
        if (previous != null && size > previous.size && previous.checkpoint <= size
                && previous.tail.equals(tail(file, previous.checkpoint))) checkpoint = previous.checkpoint;
        else removeSource(c, key);
        try (PreparedStatement pending = c.prepareStatement("DELETE FROM catalog_calls WHERE source=? AND NOT EXISTS (SELECT 1 FROM retired_sessions r WHERE r.session=catalog_calls.session) AND ordinal>=?")) {
            pending.setString(1, key); pending.setLong(2, checkpoint); pending.executeUpdate();
        }
        long position = checkpoint;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO catalog_calls VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            channel.position(checkpoint);
            try (BufferedInputStream in = new BufferedInputStream(Channels.newInputStream(channel))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int next;
                // Snapshot length: do not chase a concurrently growing append stream forever.
                while (position < size && (next = in.read()) != -1) {
                    position++;
                    if (next != '\n') { line.write(next); continue; }
                    String json = line.toString(StandardCharsets.UTF_8).trim();
                    line.reset();
                    long ordinal = checkpoint;
                    checkpoint = position;
                    insertCatalogLine(p, key, ordinal, json);
                }
                // Legacy imports may have valid JSON without a final newline. Project it now,
                // but leave the checkpoint before it so an append reparses/replaces that row.
                if (line.size() > 0) insertCatalogLine(p, key, checkpoint, line.toString(StandardCharsets.UTF_8).trim());
            }
        }
        saveSource(c, key, "catalog", size, mtime, checkpoint, tail(file, checkpoint), null, null, null, null);
    }

    private void insertCatalogLine(PreparedStatement p, String key, long ordinal, String json) throws SQLException {
        ToolCallRecord record;
        try {
            record = JsonUtils.standardMapper().readValue(json, ToolCallRecord.class);
            if (record == null) return;
        } catch (IOException malformed) { return; }
        Object[] values = {key, ordinal, json, record.getToolName(), record.getToolInputSummary(),
                record.getToolInput(), record.getSessionId(), record.getCategory(), record.getAgentName(),
                record.getProjectDirectory(), record.getTimestamp(), record.getSource(),
                record.isError() ? 1 : 0};
        bind(p, Arrays.asList(values)); p.executeUpdate();
    }

    public Page search(String query, String sessionPrefix, String agent, String project,
                       String since, String until, int offset, int limit) throws SQLException, IOException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("FTS query is required");
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("offset >= 0 and limit 1..200 required");
        syncTranscripts();
        List<Object> args = new ArrayList<>();
        args.add(query);
        StringBuilder where = new StringBuilder(" WHERE transcript_fts MATCH ? AND NOT EXISTS " +
                "(SELECT 1 FROM retired_sessions r WHERE r.session=s.session)");
        if (sessionPrefix != null && !sessionPrefix.isEmpty()) {
            where.append(" AND substr(s.session,1,length(?))=?"); args.add(sessionPrefix); args.add(sessionPrefix);
        }
        if (agent != null && !agent.isEmpty()) {
            where.append(" AND instr(java_lower(s.agent),?)>0"); args.add(agent.toLowerCase());
        }
        if (project != null && !project.isEmpty()) projectFilter(where, args, "s.project", project);
        if (since != null && !since.isEmpty()) { where.append(" AND s.started>=?"); args.add(dateBound(since, false)); }
        if (until != null && !until.isEmpty()) { where.append(" AND s.started<=?"); args.add(dateBound(until, true)); }
        args.add(limit + 1); args.add(offset);
        List<Hit> hits = new ArrayList<>();
        try (Connection c = open(true); PreparedStatement p = c.prepareStatement(
                "SELECT s.session,s.agent,s.project,s.started,t.line,t.part," +
                "snippet(transcript_fts,0,'[',']',' … ',32),bm25(transcript_fts) " +
                "FROM transcript_fts JOIN transcript_chunks t ON t.id=transcript_fts.rowid " +
                "JOIN search_sources s ON s.path=t.source" + where +
                " ORDER BY bm25(transcript_fts),s.session,t.line,t.part LIMIT ? OFFSET ?")) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    String snippet = r.getString(7);
                    if (snippet.length() > 1200) snippet = snippet.substring(0, 1200) + " …";
                    hits.add(new Hit(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getInt(5), r.getInt(6), snippet, r.getDouble(8)));
                }
            }
        }
        boolean more = hits.size() > limit;
        if (more) hits.remove(hits.size() - 1);
        return new Page(List.copyOf(hits), offset, more ? offset + limit : null);
    }

    private static void projectFilter(StringBuilder sql, List<Object> args, String column, String project) {
        String normalized = project.replace('\\', '/');
        String expr = "replace(" + column + ",char(92),'/')";
        sql.append(" AND (").append(expr).append("=? OR substr(").append(expr)
                .append(",-length(?))=?)");
        args.add(normalized); args.add("/" + normalized); args.add("/" + normalized);
    }

    private static String dateBound(String value, boolean end) {
        try {
            if (value.length() == 10) {
                java.time.LocalDate.parse(value);
                return value + (end ? " 23:59:59.999999999" : " 00:00:00");
            }
            return java.time.LocalDateTime.parse(value.replace(' ', 'T'))
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace('T', ' ');
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("Dates require YYYY-MM-DD or a local ISO date/time", invalid);
        }
    }

    public List<ToolCallRecord> catalogSearch(String query, String tool, String session, String category,
                                              String agent, String project, ToolCallIndex.SortField field,
                                              ToolCallIndex.SortDirection direction, int limit)
            throws SQLException, IOException {
        return catalogQuery(query, tool, session, category, agent, project, field, direction, limit, null);
    }

    public List<ToolCallRecord> catalogGrouped(String query, String tool, String session, String category,
                                               String agent, String project, ToolCallIndex.SortField field,
                                               ToolCallIndex.SortDirection direction, int limit, boolean byProject)
            throws SQLException, IOException {
        String partition = byProject ? "coalesce(project,'(unknown)')" : "coalesce(category,'general')";
        return catalogQuery(query, tool, session, category, agent, project, field, direction,
                limit > 0 ? limit : Integer.MAX_VALUE, partition);
    }

    private List<ToolCallRecord> catalogQuery(String query, String tool, String session, String category,
                                             String agent, String project, ToolCallIndex.SortField field,
                                             ToolCallIndex.SortDirection direction, int limit, String partition)
            throws SQLException, IOException {
        syncToolCalls();
        String source = session == null ? COMBINED : "tool-calls/" + session + ".jsonl";
        List<Object> args = new ArrayList<>(); args.add(source);
        StringBuilder where = new StringBuilder(" WHERE source=? AND NOT EXISTS " +
                "(SELECT 1 FROM retired_sessions r WHERE r.session=catalog_calls.session)");
        exactFilter(where, args, "tool", tool);
        exactFilter(where, args, "category", category);
        exactFilter(where, args, "agent", agent);
        if (project != null) projectFilter(where, args, "project", project);
        if (query != null) {
            where.append(" AND (");
            String[] columns = {"tool", "summary", "input", "session", "category", "agent", "project"};
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) where.append(" OR ");
                where.append("instr(java_lower(").append(columns[i]).append("),?)>0");
                args.add(query.toLowerCase());
            }
            where.append(')');
        }
        String column = switch (field == null ? ToolCallIndex.SortField.TIMESTAMP : field) {
            case TIMESTAMP -> "timestamp"; case TOOL_NAME -> "tool"; case CATEGORY -> "category";
            case AGENT -> "agent"; case PROJECT -> "project";
        };
        String order = "coalesce(" + column + ",'')" + (column.equals("timestamp") ? "" : " COLLATE JAVA_NOCASE") +
                (direction == ToolCallIndex.SortDirection.ASC ? " ASC" : " DESC") + ",ordinal ASC";
        args.add(limit > 0 ? limit : 100);
        String sql = partition == null
                ? "SELECT json FROM catalog_calls" + where + " ORDER BY " + order + " LIMIT ?"
                : "SELECT json FROM (SELECT catalog_calls.*,row_number() OVER (PARTITION BY " + partition
                    + " ORDER BY " + order + ") AS group_rank FROM catalog_calls" + where
                    + ") WHERE group_rank<=? ORDER BY " + order;
        List<ToolCallRecord> result = new ArrayList<>();
        try (Connection c = open(true); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) result.add(JsonUtils.standardMapper().readValue(r.getString(1), ToolCallRecord.class));
            }
        }
        return result;
    }

    private static void exactFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null) { sql.append(" AND ").append(column).append("=? COLLATE JAVA_NOCASE"); args.add(value); }
    }

    public Map<String, Object> catalogStats() throws SQLException, IOException {
        syncToolCalls();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection c = open(true)) {
            c.setAutoCommit(false); // All aggregates describe one short read snapshot.
            try (PreparedStatement p = c.prepareStatement("SELECT count(*),coalesce(sum(error),0),count(DISTINCT session) " +
                    "FROM catalog_calls WHERE source=? AND NOT EXISTS (SELECT 1 FROM retired_sessions r WHERE r.session=catalog_calls.session)")) {
                p.setString(1, COMBINED);
                try (ResultSet r = p.executeQuery()) {
                    r.next(); result.put("totalToolCalls", r.getInt(1)); result.put("totalErrors", r.getLong(2));
                    result.put("sessionCount", r.getInt(3));
                }
            }
            String[] columns = {"tool", "category", "agent", "origin", "project"};
            String[] keys = {"byTool", "byCategory", "byAgent", "bySource", "byProject"};
            for (int i = 0; i < columns.length; i++) {
                Map<String, Long> counts = new LinkedHashMap<>();
                try (PreparedStatement p = c.prepareStatement("SELECT " + columns[i] + ",count(*) AS n " +
                        "FROM catalog_calls WHERE source=? AND NOT EXISTS (SELECT 1 FROM retired_sessions r WHERE r.session=catalog_calls.session) AND " + columns[i] + " IS NOT NULL GROUP BY " +
                        columns[i] + " ORDER BY n DESC," + columns[i])) {
                    p.setString(1, COMBINED);
                    try (ResultSet r = p.executeQuery()) { while (r.next()) counts.put(r.getString(1), r.getLong(2)); }
                }
                result.put(keys[i], counts);
            }
            @SuppressWarnings("unchecked") Map<String, Long> tools = (Map<String, Long>) result.get("byTool");
            Map<String, Long> top = new LinkedHashMap<>();
            tools.entrySet().stream().limit(10).forEach(e -> top.put(e.getKey(), e.getValue()));
            result.put("topTools", top);
            c.commit();
        }
        return result;
    }

    public List<String> catalogDistinct(String column) throws SQLException, IOException {
        if (!Set.of("tool", "category", "session", "project").contains(column)) throw new IllegalArgumentException("Invalid facet");
        syncToolCalls();
        List<String> result = new ArrayList<>();
        String order = column.equals("session") ? "min(ordinal)" : column + " COLLATE JAVA_BINARY";
        try (Connection c = open(true); PreparedStatement p = c.prepareStatement("SELECT " + column +
                " FROM catalog_calls WHERE source=? AND NOT EXISTS (SELECT 1 FROM retired_sessions r WHERE r.session=catalog_calls.session) AND " + column + " IS NOT NULL GROUP BY " + column + " ORDER BY " + order)) {
            p.setString(1, COMBINED);
            try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }

    private static void bind(PreparedStatement p, List<?> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) p.setObject(i + 1, args.get(i));
    }
}
