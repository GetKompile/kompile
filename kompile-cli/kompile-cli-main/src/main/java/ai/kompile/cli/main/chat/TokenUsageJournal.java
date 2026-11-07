package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, per-session journal of provider-reported token usage: one line per
 * {@link ChatSessionMetrics#recordTokenUsage} call, stamped with time, model and project,
 * so usage can be charted over time across every chat of a project. Session-owned
 * ({@code <conversations>/<sessionId>.token-usage.jsonl}) so retention archives it with
 * the transcript.
 */
public final class TokenUsageJournal {
    public static final String SUFFIX = ".token-usage.jsonl";
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /** {@code model} is null when the route never reported one (e.g. emulated passthrough agents). */
    public record Entry(Instant at, String sessionId, String projectDirectory, String agent, String provider,
                        String model, long input, long output, long cacheRead, long cacheWrite) {
        public long total() { return input + output + cacheRead + cacheWrite; }
    }

    /** Entries matching a project and window plus what the scan could not use. */
    public record Scan(List<Entry> entries, int sessionFiles, int unreadableFiles, int malformedLines) { }

    private TokenUsageJournal() { }

    public static Path pathFor(Path conversationsDir, String sessionId) {
        return conversationsDir.resolve(sessionId + SUFFIX);
    }

    public static void append(Path journal, Entry entry) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("at", entry.at().toString());
        node.put("sessionId", entry.sessionId());
        if (entry.projectDirectory() != null) node.put("projectDirectory", entry.projectDirectory());
        if (entry.agent() != null) node.put("agent", entry.agent());
        if (entry.provider() != null) node.put("provider", entry.provider());
        if (entry.model() != null) node.put("model", entry.model());
        node.put("input", entry.input());
        node.put("output", entry.output());
        node.put("cacheRead", entry.cacheRead());
        node.put("cacheWrite", entry.cacheWrite());
        Files.createDirectories(journal.toAbsolutePath().getParent());
        Files.writeString(journal, MAPPER.writeValueAsString(node) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /**
     * Reads every session journal for {@code projectDirectory} with entries in
     * {@code [from, to)}. Journals last modified before {@code from} cannot hold
     * matching entries and are skipped without being opened.
     */
    public static Scan read(Path conversationsDir, String projectDirectory, Instant from, Instant to) {
        List<Entry> entries = new ArrayList<>();
        int files = 0, unreadable = 0, malformed = 0;
        if (conversationsDir == null || !Files.isDirectory(conversationsDir, LinkOption.NOFOLLOW_LINKS)) {
            return new Scan(List.of(), 0, 0, 0);
        }
        String project = normalize(projectDirectory);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(conversationsDir, "*" + SUFFIX)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(from)) continue;
                } catch (IOException e) {
                    unreadable++;
                    continue;
                }
                files++;
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        Entry entry = parse(line);
                        if (entry == null) { malformed++; continue; }
                        if (!project.equals(normalize(entry.projectDirectory()))) continue;
                        if (entry.at().isBefore(from) || !entry.at().isBefore(to)) continue;
                        entries.add(entry);
                    }
                } catch (IOException e) {
                    unreadable++;
                }
            }
        } catch (IOException e) {
            unreadable++;
        }
        entries.sort(java.util.Comparator.comparing(Entry::at));
        return new Scan(List.copyOf(entries), files, unreadable, malformed);
    }

    private static Entry parse(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            if (!node.hasNonNull("at") || !node.hasNonNull("sessionId")) return null;
            return new Entry(Instant.parse(node.get("at").asText()), node.get("sessionId").asText(),
                    text(node, "projectDirectory"), text(node, "agent"), text(node, "provider"), text(node, "model"),
                    Math.max(0, node.path("input").asLong(0)), Math.max(0, node.path("output").asLong(0)),
                    Math.max(0, node.path("cacheRead").asLong(0)), Math.max(0, node.path("cacheWrite").asLong(0)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    static String normalize(String directory) {
        if (directory == null || directory.isBlank()) return "";
        try {
            return Path.of(directory).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return directory.strip();
        }
    }
}
