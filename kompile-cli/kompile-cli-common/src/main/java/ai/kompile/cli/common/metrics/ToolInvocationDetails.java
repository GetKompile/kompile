package ai.kompile.cli.common.metrics;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** Exact invocation-keyed content, separate from the bounded token journal. */
public final class ToolInvocationDetails {
    private static final ObjectMapper JSON = JsonUtils.newStandardMapper();
    public static final int PAGE_CHARS = 32768;
    private static final Set<String> FIELDS = Set.of("arguments", "output", "rawOutput", "structured");
    private final Path root;

    public ToolInvocationDetails(Path toolCallsDirectory) {
        root = toolCallsDirectory.resolve("details").toAbsolutePath().normalize();
    }

    private Path directory(String session, String invocation) {
        if (!safeId(session) || !safeId(invocation)) throw new IllegalArgumentException("Invalid session or invocation ID");
        Path dir = root.resolve(session).resolve(invocation);
        // Never follow a user-created symlink out of the managed detail tree.
        for (Path p = dir; p != null && p.startsWith(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("Symlinked invocation details are not allowed");
        }
        return dir;
    }

    public static boolean safeId(String id) {
        return id != null && id.length() <= 256 && id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")
                && !id.equals(".") && !id.equals("..");
    }

    public void request(ToolInvocationContext context, String arguments) throws IOException {
        Path dir = directory(context.sessionId(), context.invocationId());
        Files.createDirectories(dir);
        write(dir.resolve("context.json"), context.toJsonNode(JSON).toString());
        write(dir.resolve("arguments.txt"), arguments == null ? "{}" : arguments);
    }

    public void result(ToolInvocationContext context, String output, String rawOutput, String structured) throws IOException {
        Path dir = directory(context.sessionId(), context.invocationId());
        Files.createDirectories(dir);
        if (output != null) write(dir.resolve("output.txt"), output);
        if (rawOutput != null) write(dir.resolve("rawOutput.txt"), rawOutput);
        if (structured != null) write(dir.resolve("structured.txt"), structured);
    }

    private static void write(Path file, String content) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Symlinked detail file");
        Path temporary = Files.createTempFile(file.getParent(), ".detail-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public ObjectNode read(String session, String invocation) throws IOException {
        Path dir = directory(session, invocation);
        ObjectNode node = JSON.createObjectNode();
        Path context = dir.resolve("context.json");
        if (Files.isRegularFile(context) && !Files.isSymbolicLink(context) && Files.size(context) <= 65536) {
            JsonNode parsed = JSON.readTree(context.toFile());
            node.set("context", parsed);
            node.put("available", true);
        } else {
            node.put("available", false);
            node.put("status", "Invocation content was not recorded for this historical call.");
        }
        for (String field : FIELDS) node.set(field, page(session, invocation, field, 0));
        return node;
    }

    /** Character offsets keep UTF-8 and JSON content readable without cutting bytes mid-codepoint. */
    public ObjectNode page(String session, String invocation, String field, long offset) throws IOException {
        if (!FIELDS.contains(field) || offset < 0 || offset > 100_000_000L) throw new IllegalArgumentException("Invalid detail page");
        Path file = directory(session, invocation).resolve(field + ".txt");
        ObjectNode page = JSON.createObjectNode();
        page.put("offset", offset);
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            page.put("available", false);
            page.put("status", "Not recorded");
            return page;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = reader.skip(offset - skipped);
                if (n == 0) { if (reader.read() < 0) break; n = 1; }
                skipped += n;
            }
            char[] chars = new char[PAGE_CHARS + 1];
            int count = 0;
            while (count < PAGE_CHARS) {
                int n = reader.read(chars, count, PAGE_CHARS - count);
                if (n < 0) break;
                count += n;
            }
            // A supplementary Unicode character may straddle the normal page boundary.
            if (count > 0 && Character.isHighSurrogate(chars[count - 1])) {
                int next = reader.read();
                if (next >= 0) chars[count++] = (char) next;
            }
            page.put("available", true);
            page.put("text", new String(chars, 0, count));
            page.put("nextOffset", skipped + count);
            page.put("hasMore", reader.read() >= 0);
            page.put("sizeBytes", Files.size(file));
        }
        return page;
    }
}
