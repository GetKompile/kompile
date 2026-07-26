package ai.kompile.project.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical, dependency-free rules shared by project-store, desktop clients, and Android.
 *
 * <p>Portable knowledge deliberately excludes model binaries, credentials, settings, logs, and
 * arbitrary project files. A knowledge revision is content-addressed from project identity,
 * default graph, and the sorted full inventory, so clients can apply a delta without coupling the
 * model installation revision to the knowledge revision.</p>
 */
public final class PortableKnowledge {
    public static final int FORMAT_VERSION = 1;
    public static final String PROJECT_DESCRIPTOR = "kompile.project.json";
    public static final String MARKDOWN_ROOT = "data/markdown/";
    public static final String GRAPH_ROOT = "data/graph/";
    public static final String FACT_SHEET_ROOT = "data/fact-sheets/";
    public static final String INDEX_ROOT = "data/indexes/";
    public static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_PATH_LENGTH = 512;

    private static final List<String> PORTABLE_ROOTS = List.of(
            MARKDOWN_ROOT, GRAPH_ROOT, FACT_SHEET_ROOT, INDEX_ROOT);

    private PortableKnowledge() {
    }

    public static boolean isPortablePath(String value) {
        try {
            String path = normalizePath(value);
            if (PROJECT_DESCRIPTOR.equals(path)) {
                return true;
            }
            for (String root : PORTABLE_ROOTS) {
                if (path.startsWith(root) && path.length() > root.length()) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String requirePortablePath(String value) {
        String path = normalizePath(value);
        if (!isPortablePath(path)) {
            throw new IllegalArgumentException("Path is not portable knowledge: " + path);
        }
        return path;
    }

    public static String requireMarkdownPath(String value) {
        String path = requirePortablePath(value);
        if (!path.startsWith(MARKDOWN_ROOT) || !path.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("Mobile source must be Markdown under " + MARKDOWN_ROOT + ": " + path);
        }
        return path;
    }

    public static String normalizePath(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Path is required");
        }
        String path = value.trim();
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Invalid portable path length");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Portable paths must be relative ZIP paths: " + value);
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Portable path contains an unsafe segment: " + value);
            }
        }
        return path;
    }

    public static String pathKey(String value) {
        return normalizePath(value).toLowerCase(Locale.ROOT);
    }

    public static List<Entry> normalizeInventory(Collection<Entry> entries) {
        Objects.requireNonNull(entries, "Inventory is required");
        List<Entry> normalized = new ArrayList<>(entries.size());
        Set<String> keys = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("Inventory contains a null entry");
            }
            Entry checked = new Entry(entry.path(), entry.size(), entry.sha256());
            if (!keys.add(pathKey(checked.path()))) {
                throw new IllegalArgumentException("Inventory has a duplicate or case-colliding path: " + checked.path());
            }
            normalized.add(checked);
        }
        normalized.sort(Comparator.comparing(Entry::path));
        return List.copyOf(normalized);
    }

    public static String revision(
            String projectId,
            String projectName,
            String defaultGraph,
            Collection<Entry> entries) {
        String id = requireIdentity(projectId, "projectId");
        String name = requireIdentity(projectName, "projectName");
        String graph = defaultGraph == null || defaultGraph.isBlank()
                ? ""
                : requirePortablePath(defaultGraph);
        MessageDigest digest = sha256Digest();
        updateField(digest, "kompile-portable-knowledge-v1");
        updateField(digest, id);
        updateField(digest, name);
        updateField(digest, graph);
        for (Entry entry : normalizeInventory(entries)) {
            updateField(digest, entry.path());
            updateField(digest, Long.toString(entry.size()));
            updateField(digest, entry.sha256());
        }
        return hex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "Bytes are required");
        return hex(sha256Digest().digest(bytes));
    }

    public static String sha256(InputStream input) throws IOException {
        Objects.requireNonNull(input, "Input is required");
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String requireRevision(String value, String field) {
        String revision = requireIdentity(value, field).toLowerCase(Locale.ROOT);
        if (revision.length() != 64) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        for (int i = 0; i < revision.length(); i++) {
            char c = revision.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
            }
        }
        return revision;
    }

    public static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) ((bytes.length >>> 24) & 0xff));
        digest.update((byte) ((bytes.length >>> 16) & 0xff));
        digest.update((byte) ((bytes.length >>> 8) & 0xff));
        digest.update((byte) (bytes.length & 0xff));
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    public record Entry(String path, long size, String sha256) {
        public Entry {
            path = requirePortablePath(path);
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Invalid portable entry size for " + path + ": " + size);
            }
            sha256 = requireRevision(sha256, "sha256");
        }
    }
}
