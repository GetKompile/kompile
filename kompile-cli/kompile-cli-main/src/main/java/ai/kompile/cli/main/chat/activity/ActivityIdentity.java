package ai.kompile.cli.main.chat.activity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable identity namespaces a conversation, run, and actor without conflating their IDs. */
public record ActivityIdentity(
        String source,
        String conversationId,
        String runId,
        String actorId,
        String parentActorId,
        String projectDirectory) {

    public ActivityIdentity {
        source = normalize(source, "kompile", 80);
        conversationId = required(conversationId, "conversationId", 240);
        runId = normalize(runId, "", 240);
        actorId = normalize(actorId, "", 200);
        parentActorId = normalize(parentActorId, "", 200);
        projectDirectory = normalizePath(projectDirectory);
    }

    public static ActivityIdentity conversation(String conversationId, Path projectDirectory) {
        return new ActivityIdentity("kompile", conversationId, "", "", "",
                projectDirectory == null ? null : projectDirectory.toAbsolutePath().normalize().toString());
    }

    public String key() {
        return source + ":" + conversationId;
    }

    public String storageKey() {
        return safeSegment(source) + "__" + safeSegment(conversationId);
    }

    private static String required(String value, String name, int max) {
        String normalized = normalize(value, "", max);
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String normalize(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (normalized.length() <= max) return normalized;
        String hash = shortHash(normalized);
        int suffix = hash.length() + 1;
        return normalized.substring(0, Math.max(1, max - suffix)) + "~" + hash;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return normalize(value, "", 512);
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) result.append(String.format(java.util.Locale.ROOT,
                    "%02x", digest[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Package-private so ActivityStorage uses the exact same collision-resistant encoding. */
    static String storageSegment(String value) {
        if (value == null || value.isBlank()) return "_unknown";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                out.append(c);
            } else {
                out.append('~').append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
            }
        }
        String result = out.toString();
        if (result.isBlank() || ".".equals(result) || "..".equals(result)) return "_unknown";
        if (result.length() <= 240) return result;
        String hash = shortHash(value);
        int suffix = hash.length() + 1;
        return result.substring(0, Math.max(1, 240 - suffix)) + "~" + hash;
    }

    static String restoreSegment(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '~' && i + 4 < value.length()) {
                String hex = value.substring(i + 1, i + 5);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                    continue;
                } catch (NumberFormatException ignored) {
                    // A legacy/non-encoded segment may contain a literal '~'.
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String safeSegment(String value) {
        return storageSegment(value);
    }
}
