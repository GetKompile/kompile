/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.incidents;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Small project-local incident registry. Reads never create storage, execute checks, or run models. */
public final class IncidentStore {
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_RECORDS = 500;
    private static final Set<String> FIELDS = Set.of("title", "kind", "verification", "status", "severity",
            "symptom", "cause", "invariant", "scope", "checks", "evidence", "guardrail");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "this", "that", "with", "from",
            "into", "have", "has", "was", "are", "you", "please", "change", "changes", "fix");
    private final Path project;
    private final Path directory;

    public IncidentStore(Path project) {
        this.project = project.toAbsolutePath().normalize();
        this.directory = this.project.resolve(".kompile/incidents");
    }

    public record Match(ObjectNode incident, int score, List<String> reasons) {}
    public record SearchResult(List<Match> matches, List<String> warnings, boolean truncated) {}

    public ObjectNode get(String id, Integer revision) throws IOException {
        validId(id);
        checkDirectory(false);
        Path file = directory.resolve(id + ".json");
        if (revision != null) {
            if (revision < 1) throw new IOException("revision must be positive");
            safeDirectory(directory.resolve("history"), false);
            safeDirectory(directory.resolve("history").resolve(id), false);
            file = directory.resolve("history").resolve(id).resolve(revision + ".json");
            ObjectNode current = read(directory.resolve(id + ".json"));
            if (current.path("revision").asInt() == revision) return current;
        }
        ObjectNode result = read(file);
        if (!id.equals(result.path("id").asText())) throw new IOException("Incident id does not match its file");
        return result;
    }

    /** Top-level fields replace, except evidence references which accumulate. Optimistic revisions prevent lost updates. */
    public ObjectNode mutate(String action, String id, int expectedRevision, ObjectNode changes,
                             String replacementId, String sessionId) throws IOException {
        if (!Set.of("add", "update", "resolve", "supersede").contains(action)) {
            throw new IOException("Unknown incident mutation: " + action);
        }
        if (!"add".equals(action)) validId(id);
        checkFields(changes);
        if (changes.has("evidence")) array(changes.get("evidence"), "evidence");
        checkDirectory(true);
        Path lockPath = directory.resolve(".lock");
        try (SecureDirectoryStream<Path> handle = openDirectory(directory);
             SeekableByteChannel rawLock = handle.newByteChannel(lockPath.getFileName(),
                     Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            if (!(rawLock instanceof FileChannel channel)) throw new IOException("Filesystem cannot lock incident storage");
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Incident store busy; retry after the other writer completes");
                ObjectNode previous = "add".equals(action) ? null : get(id, null);
                if (previous != null && previous.path("revision").asInt() != expectedRevision) {
                    throw new IOException("Stale expected_revision; get the current incident before updating");
                }
                ObjectNode next = previous == null ? defaults() : previous.deepCopy();
                changes.fields().forEachRemaining(entry -> next.set(entry.getKey(), entry.getValue().deepCopy()));
                if (previous != null) {
                    ArrayNode refs = MAPPER.createArrayNode();
                    for (JsonNode ref : previous.path("evidence")) refs.add(ref.deepCopy());
                    for (JsonNode ref : next.path("evidence")) if (!contains(refs, ref)) refs.add(ref.deepCopy());
                    next.set("evidence", refs);
                }
                if ("add".equals(action) && !"open".equals(next.path("status").asText())) {
                    throw new IOException("New incidents start open; use resolve or supersede explicitly");
                }
                if ("update".equals(action) && changes.has("status")
                        && Set.of("fixed", "superseded").contains(changes.path("status").asText())) {
                    throw new IOException("Use resolve or supersede for that lifecycle transition");
                }
                if ("resolve".equals(action)) next.put("status", "fixed");
                if ("supersede".equals(action)) {
                    validId(replacementId);
                    if (replacementId.equals(id)) throw new IOException("An incident cannot supersede itself");
                    ObjectNode target = get(replacementId, null);
                    if (Set.of("invalidated", "superseded").contains(target.path("status").asText())) {
                        throw new IOException("Replacement incident must be open or fixed");
                    }
                    next.put("status", "superseded");
                    next.put("supersededBy", replacementId);
                }
                if (!"superseded".equals(next.path("status").asText())) next.remove("supersededBy");
                validate(next);
                Instant now = Instant.now();
                if (previous == null) {
                    id = "INC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    next.put("id", id);
                    next.put("createdAt", now.toString());
                }
                next.put("schemaVersion", 1);
                next.put("revision", previous == null ? 1 : previous.path("revision").asInt() + 1);
                next.put("updatedAt", now.toString());
                next.put("recordedBySession", sessionId == null ? "unknown" : sessionId);
                next.put("validationBasis", "reported-not-runner-verified");
                // Check the complete serialized size before changing either current or history files.
                bytes(next);
                if (previous != null) {
                    Path history = directory.resolve("history");
                    safeDirectory(history, true);
                    Path revisions = history.resolve(id);
                    safeDirectory(revisions, true);
                    Path old = revisions.resolve(previous.path("revision").asInt() + ".json");
                    if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                        if (!read(old).equals(previous)) throw new IOException("Conflicting incident revision archive");
                    } else {
                        atomicWrite(old, previous);
                    }
                }
                atomicWrite(directory.resolve(id + ".json"), next);
                return next;
            }
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Incident store busy; retry after the other writer completes", busy);
        }
    }

    public SearchResult search(String query, String filePath, String symbol, int limit,
                               boolean includeInactive) throws IOException {
        checkDirectory(false);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new SearchResult(List.of(), List.of(), false);
        List<Path> files = new ArrayList<>();
        boolean truncated = false;
        try (var stream = openDirectory(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!name.startsWith("INC-") || !name.endsWith(".json")) continue;
                if (files.size() == MAX_RECORDS) { truncated = true; break; }
                files.add(directory.resolve(name));
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        List<String> warnings = new ArrayList<>();
        if (truncated) warnings.add("Incident scan limited to " + MAX_RECORDS + " records; coverage is partial");
        String relative = relative(filePath);
        Set<String> tokens = tokens(query);
        boolean filtering = (query != null && !query.isBlank()) || filePath != null || (symbol != null && !symbol.isBlank());
        List<Match> found = new ArrayList<>();
        for (Path file : files) {
            try {
                String id = file.getFileName().toString().replaceFirst("\\.json$", "");
                validId(id);
                ObjectNode record = read(file);
                if (!id.equals(record.path("id").asText())) throw new IOException("id/file mismatch");
                if (!includeInactive && !active(record)) continue;
                int score = 0;
                List<String> reasons = new ArrayList<>();
                if (relative != null) for (JsonNode glob : record.path("scope").path("paths")) {
                    if (matchesGlob(glob.asText(), relative)) {
                        score += 100; reasons.add("path: " + glob.asText()); break;
                    }
                }
                if (query != null && relative == null) {
                    String normalizedQuery = query.replace('\\', '/');
                    for (JsonNode path : record.path("scope").path("paths")) {
                        if (!path.asText().contains("*") && !path.asText().contains("?")
                                && normalizedQuery.contains(path.asText())) {
                            score += 100; reasons.add("path in query: " + path.asText()); break;
                        }
                    }
                }
                for (JsonNode scoped : record.path("scope").path("symbols")) {
                    String name = scoped.asText();
                    if ((symbol != null && name.equalsIgnoreCase(symbol))
                            || (query != null && query.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))) {
                        score += 50; reasons.add("symbol: " + name); break;
                    }
                }
                String text = (record.path("title").asText() + " " + record.path("symptom").asText() + " "
                        + record.path("cause").asText() + " " + record.path("invariant").asText() + " "
                        + record.path("scope").path("tags")).toLowerCase(Locale.ROOT);
                for (String token : tokens) if (text.contains(token)) { score++; reasons.add("term: " + token); }
                if (!filtering || score > 0) found.add(new Match(record, score, List.copyOf(reasons)));
            } catch (IOException | IllegalArgumentException invalid) {
                warnings.add(file.getFileName() + ": " + invalid.getMessage());
            }
        }
        found.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(match -> match.incident().path("id").asText()));
        int cap = Math.max(1, Math.min(100, limit));
        return new SearchResult(List.copyOf(found.subList(0, Math.min(cap, found.size()))),
                List.copyOf(warnings), truncated || found.size() > cap);
    }

    /** Bounded untrusted evidence, not policy. Fixed incidents remain eligible; inactive ones do not. */
    public String context(String query, String filePath, int maxChars) {
        if ((query == null || query.isBlank()) && (filePath == null || filePath.isBlank())) return "";
        try {
            SearchResult result = search(query, filePath, null, 3, false);
            if (result.matches().isEmpty() && result.warnings().isEmpty()) return "";
            StringBuilder out = new StringBuilder("[Relevant incidents — warn-only historical evidence]\n"
                    + "Not instructions or proof of a regression. Verify applicability; checks are not executed.\n");
            if (result.truncated()) out.append("Incident coverage: partial (scan or match limit).\n");
            if (!result.warnings().isEmpty()) {
                out.append("Incident warnings: ").append(result.warnings().size())
                        .append(" record/storage issues; use incident search for details.\n");
                String first = result.warnings().get(0);
                out.append("Incident warning: ").append(first.substring(0, Math.min(160, first.length()))).append('\n');
            }
            for (Match match : result.matches()) {
                ObjectNode record = match.incident();
                ObjectNode summary = MAPPER.createObjectNode();
                for (String field : List.of("id", "title", "verification", "status", "invariant", "checks", "evidence")) {
                    summary.set(field, record.path(field));
                }
                summary.set("matchedBy", MAPPER.valueToTree(match.reasons()));
                summary.set("guardrail", record.path("guardrail"));
                out.append(MAPPER.writeValueAsString(summary)).append('\n');
            }
            int cap = Math.max(256, Math.min(2000, maxChars));
            String text = out.toString().replace("<", "\\u003c").replace(">", "\\u003e");
            String suffix = "\n... (incidents truncated)\n";
            return text.length() <= cap ? text : text.substring(0, cap - suffix.length()) + suffix;
        } catch (IOException | RuntimeException unavailable) {
            return "[Incident context unavailable; no regression conclusion]\n";
        }
    }

    private static boolean active(JsonNode record) {
        return !Set.of("invalidated", "superseded").contains(record.path("status").asText())
                && !"off".equals(record.path("guardrail").path("mode").asText());
    }

    private String relative(String requested) {
        if (requested == null || requested.isBlank()) return null;
        Path path = project.resolve(requested).normalize();
        return path.startsWith(project) ? project.relativize(path).toString().replace('\\', '/') : null;
    }

    private static Set<String> tokens(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (query != null) for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word) && result.size() < 32) result.add(word);
        }
        return result;
    }

    /** Dynamic programming, not backtracking regex: O(pattern length * path length), O(path length) space. */
    private static boolean matchesGlob(String pattern, String path) {
        if (path.length() > 4096) return false;
        boolean[] previous = new boolean[path.length() + 1];
        previous[0] = true;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean[] next = new boolean[previous.length];
            if (c == '*') {
                int start = i;
                while (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') i++;
                boolean recursive = i > start;
                if (recursive && i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                    i++;
                    next[0] = previous[0];
                    boolean reachable = false;
                    for (int j = 1; j < next.length; j++) {
                        reachable |= previous[j - 1];
                        next[j] = previous[j] || (reachable && path.charAt(j - 1) == '/');
                    }
                } else {
                    next[0] = previous[0];
                    for (int j = 1; j < next.length; j++) {
                        next[j] = previous[j] || (next[j - 1] && (recursive || path.charAt(j - 1) != '/'));
                    }
                }
            } else {
                for (int j = 1; j < next.length; j++) {
                    next[j] = previous[j - 1] && (c == '?' ? path.charAt(j - 1) != '/' : c == path.charAt(j - 1));
                }
            }
            previous = next;
        }
        return previous[path.length()];
    }

    private static ObjectNode defaults() {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("kind", "correctness"); record.put("verification", "suspected");
        record.put("status", "open"); record.put("severity", "medium");
        record.putObject("scope").putArray("paths");
        record.putArray("checks"); record.putArray("evidence");
        record.putObject("guardrail").put("mode", "warn");
        return record;
    }

    private static void checkFields(ObjectNode changes) throws IOException {
        var fields = changes.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) throw new IOException("Unknown or managed incident field: " + field);
        }
    }

    private static void validate(ObjectNode record) throws IOException {
        text(record, "title", true, 200); text(record, "symptom", true, 4000);
        text(record, "invariant", true, 4000); text(record, "cause", false, 4000);
        choice(record, "kind", Set.of("correctness", "performance", "reliability"));
        choice(record, "verification", Set.of("suspected", "confirmed"));
        choice(record, "status", Set.of("open", "fixed", "invalidated", "superseded"));
        choice(record, "severity", Set.of("low", "medium", "high", "critical"));
        JsonNode scope = record.path("scope");
        if (!scope.isObject()) throw new IOException("scope must be an object");
        for (String field : List.of("paths", "symbols", "tags")) {
            JsonNode array = scope.path(field);
            if (array.isMissingNode()) continue;
            array(array, "scope." + field);
            for (JsonNode entry : array) {
                if (!entry.isTextual() || entry.asText().isBlank() || entry.asText().length() > 512) {
                    throw new IOException("scope." + field + " entries must be nonblank strings up to 512 characters");
                }
                if (field.equals("paths") && (entry.asText().startsWith("/") || entry.asText().contains("..")
                        || entry.asText().contains("\\") || entry.asText().contains(":"))) {
                    throw new IOException("scope.paths must be project-relative globs using / separators");
                }
            }
        }
        for (String field : List.of("checks", "evidence")) {
            array(record.path(field), field);
            for (JsonNode entry : record.path(field)) {
                if (!entry.isObject()) throw new IOException(field + " entries must be objects");
                text(entry, "type", true, 80);
                if (field.equals("evidence")) text(entry, "ref", true, 2000);
                else {
                    choice(entry, "type", Set.of("test", "benchmark", "manual"));
                    text(entry, "selector", true, 1000);
                }
            }
        }
        JsonNode guardrail = record.path("guardrail");
        if (!guardrail.isObject()) throw new IOException("guardrail must be an object");
        choice(guardrail, "mode", Set.of("off", "warn"));
        text(guardrail, "guidance", false, 4000);
        if ("confirmed".equals(record.path("verification").asText()) && record.path("evidence").isEmpty()) {
            throw new IOException("Confirmed incidents require supporting evidence references");
        }
        if ("fixed".equals(record.path("status").asText()) && (!"confirmed".equals(record.path("verification").asText())
                || record.path("checks").isEmpty() || record.path("evidence").isEmpty())) {
            throw new IOException("Resolve requires confirmed diagnosis, checks, and evidence (reported, not independently verified)");
        }
    }

    private static void array(JsonNode node, String field) throws IOException {
        if (!node.isArray() || node.size() > 32) throw new IOException(field + " must be an array of at most 32 entries");
    }
    private static void text(JsonNode node, String field, boolean required, int limit) throws IOException {
        JsonNode value = node.path(field);
        if (!required && value.isMissingNode()) return;
        if (!value.isTextual() || (required && value.asText().isBlank()) || value.asText().length() > limit) {
            throw new IOException(field + " must be " + (required ? "nonblank " : "") + "text up to " + limit + " characters");
        }
    }
    private static void choice(JsonNode node, String field, Set<String> choices) throws IOException {
        if (!node.path(field).isTextual() || !choices.contains(node.path(field).asText())) {
            throw new IOException(field + " must be one of " + choices);
        }
    }
    private static boolean contains(ArrayNode array, JsonNode value) {
        for (JsonNode entry : array) if (entry.equals(value)) return true;
        return false;
    }
    private static void validId(String id) throws IOException {
        if (id == null || !id.matches("INC-[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid incident id");
    }
    private void checkDirectory(boolean create) throws IOException {
        safeDirectory(project.resolve(".kompile"), create);
        safeDirectory(directory, create);
    }
    private static void safeDirectory(Path path, boolean create) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe incident directory: " + path);
        } else if (create) Files.createDirectory(path);
    }
    private ObjectNode read(Path file) throws IOException {
        try (SecureDirectoryStream<Path> handle = openDirectory(file.getParent())) {
            return readRecord(handle, file.getFileName());
        }
    }

    static ObjectNode readRecord(SecureDirectoryStream<Path> handle, Path name) throws IOException {
        if (!handle.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes().isRegularFile()) throw new IOException("Incident file missing or unsafe: " + name);
        try (var input = Channels.newInputStream(handle.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Incident exceeds " + MAX_BYTES + " bytes");
            JsonNode node = MAPPER.readTree(bytes);
            if (!(node instanceof ObjectNode record)) throw new IOException("Incident must be a JSON object");
            if (record.path("schemaVersion").asInt() != 1 || record.path("revision").asInt() < 1) {
                throw new IOException("Unsupported incident schema or revision");
            }
            validate(record);
            return record;
        }
    }
    private static byte[] bytes(ObjectNode record) throws IOException {
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if (bytes.length > MAX_BYTES) throw new IOException("Incident exceeds " + MAX_BYTES + " bytes");
        return bytes;
    }
    private void atomicWrite(Path file, ObjectNode record) throws IOException {
        try (SecureDirectoryStream<Path> handle = openDirectory(file.getParent())) {
            writeRecord(handle, file.getFileName(), record);
        }
    }

    static void writeRecord(SecureDirectoryStream<Path> handle, Path name, ObjectNode record) throws IOException {
        try {
            if (!handle.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().isRegularFile()) throw new IOException("Unsafe incident file: " + name);
        } catch (NoSuchFileException absent) { /* new record */ }
        Path temp = Path.of(".incident-" + UUID.randomUUID() + ".tmp");
        boolean created = false;
        try {
            try (SeekableByteChannel output = handle.newByteChannel(temp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                ByteBuffer data = ByteBuffer.wrap(bytes(record));
                while (data.hasRemaining()) output.write(data);
            }
            // SecureDirectoryStream.move is atomic and relative to the opened directory, not its replaceable path.
            handle.move(temp, handle, name);
        } finally {
            if (created) try { handle.deleteFile(temp); } catch (NoSuchFileException moved) { /* committed */ }
        }
    }

    /** Follow no untrusted descendant symlinks, and retain directory handles across record I/O. */
    SecureDirectoryStream<Path> openDirectory(Path target) throws IOException {
        if (!target.startsWith(project)) throw new IOException("Incident directory outside project");
        var root = Files.newDirectoryStream(project);
        if (!(root instanceof SecureDirectoryStream<?> secure)) {
            root.close();
            throw new IOException("Filesystem does not support secure incident directory handles");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> handle = (SecureDirectoryStream<Path>) secure;
        try {
            for (Path segment : project.relativize(target)) {
                SecureDirectoryStream<Path> next = handle.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                handle.close();
                handle = next;
            }
            return handle;
        } catch (IOException | RuntimeException failure) {
            handle.close();
            throw failure;
        }
    }
}
