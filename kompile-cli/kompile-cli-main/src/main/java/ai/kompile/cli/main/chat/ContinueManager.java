/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.skill.ManagedFileLock;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Backing store and decision engine for the {@code /continue} chat command.
 *
 * <p>When a finished assistant turn asks the user a question ("Shall I
 * proceed?"), {@link #decide} prepares an automatic reply that
 * {@code ChatMessageHandler} dispatches as an ordinary user turn, so the agent
 * is never left waiting on a question the user already pre-answered. Matching
 * is a case-insensitive substring test of the finished response against the
 * configured trigger keywords.</p>
 *
 * <p>Configuration is project-global and persists in
 * {@code .kompile/chat-continue.json}: the enable switch, the reply text, and
 * an explicit keyword list. Until customized, the built-in default keywords and
 * reply apply. A consecutive-reply budget stops runaway yes-loops; any
 * human-submitted message re-arms it.</p>
 */
public final class ContinueManager {

    static final String PROJECT_FILE = "chat-continue.json";
    static final String DEFAULT_REPLY = "Yes, proceed.";
    static final int MAX_CONSECUTIVE_AUTO_REPLIES = 5;
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_KEYWORDS = 64;
    private static final int MAX_KEYWORD_CHARS = 200;
    private static final int MAX_REPLY_CHARS = 2_000;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final ConcurrentMap<Path, Object> JVM_FILE_LOCKS = new ConcurrentHashMap<>();

    /** Lowercase trigger keywords applied until the project customizes them. */
    static final List<String> DEFAULT_KEYWORDS = List.of(
            "proceed?",
            "shall i proceed",
            "shall i continue",
            "should i proceed",
            "should i continue",
            "continue?",
            "do you want me to",
            "would you like me to",
            "please confirm",
            "confirm to proceed",
            "awaiting your approval",
            "waiting for your approval",
            "yes or no",
            "y/n",
            "reply yes",
            "type yes",
            "need your approval");

    /**
     * Result of scanning one finished assistant turn. {@code fire} requests an
     * automatic reply; {@code notice} is a one-shot warning (budget exhausted)
     * that must be surfaced even when the reply does not fire.
     */
    public record Decision(boolean fire, String reply, String notice) {
        public static final Decision NONE = new Decision(false, null, null);
    }

    private record Snapshot(Boolean enabled, String reply, List<String> keywords) {
        static final Snapshot EMPTY = new Snapshot(null, null, List.of());
    }

    private final ObjectMapper objectMapper;
    private final Path projectFile;
    private final List<String> inMemoryKeywords; // non-null = in-memory instance
    private Boolean inMemoryEnabled;
    private String inMemoryReply;

    private final Object stateLock = new Object();
    private int consecutiveAutoReplies;
    private boolean budgetNoticeShown;

    /** File-backed manager rooted at the chat session's working directory. */
    public ContinueManager(ObjectMapper objectMapper, Path workingDirectory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Path working = (workingDirectory == null ? Path.of(".") : workingDirectory)
                .toAbsolutePath().normalize();
        Path root = new KompileProjectStore().findProjectRoot(working).orElse(working);
        this.projectFile = root.resolve(KompileProjectStore.METADATA_DIR)
                .resolve(PROJECT_FILE)
                .toAbsolutePath().normalize();
        this.inMemoryKeywords = null;
    }

    private ContinueManager(ObjectMapper objectMapper, List<String> explicitKeywords) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.projectFile = null;
        this.inMemoryKeywords = explicitKeywords == null
                ? new ArrayList<>() : new ArrayList<>(explicitKeywords);
    }

    /** In-memory manager with default keywords, for focused tests. */
    public static ContinueManager inMemory() {
        return new ContinueManager(new ObjectMapper(), (List<String>) null);
    }

    /** In-memory manager starting from an explicit keyword list (empty = defaults). */
    public static ContinueManager inMemory(List<String> explicitKeywords) {
        return new ContinueManager(new ObjectMapper(), explicitKeywords);
    }

    // ── Decision engine ──────────────────────────────────────────────────────

    /**
     * Scan a finished assistant response. Fires only when enabled, the session
     * is interactive, plan mode is off, a keyword matches, and the consecutive
     * auto-reply budget has room. The budget-exhaustion warning is returned
     * exactly once per arm.
     */
    public Decision decide(String response, boolean interactive, boolean planningMode) {
        synchronized (stateLock) {
            if (!interactive || planningMode) {
                return Decision.NONE;
            }
            Snapshot snapshot = snapshotQuietly();
            if (snapshot.enabled() != null && !snapshot.enabled()) {
                return Decision.NONE;
            }
            if (response == null || response.isBlank()) {
                return Decision.NONE;
            }
            List<String> keywords = snapshot.keywords().isEmpty()
                    ? DEFAULT_KEYWORDS : snapshot.keywords();
            String matched = null;
            String haystack = response.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (!keyword.isEmpty() && haystack.contains(keyword)) {
                    matched = keyword;
                    break;
                }
            }
            if (matched == null) {
                return Decision.NONE;
            }
            if (consecutiveAutoReplies >= MAX_CONSECUTIVE_AUTO_REPLIES) {
                if (!budgetNoticeShown) {
                    budgetNoticeShown = true;
                    return new Decision(false, null,
                            "⚠ /continue paused after " + MAX_CONSECUTIVE_AUTO_REPLIES
                                    + " consecutive auto-replies — reply manually to re-arm.");
                }
                return Decision.NONE;
            }
            String reply = snapshot.reply() == null || snapshot.reply().isBlank()
                    ? DEFAULT_REPLY : snapshot.reply();
            return new Decision(true, reply, null);
        }
    }

    /** Snapshot for decisions; one damaged/unreadable file never disables the feature. */
    private Snapshot snapshotQuietly() {
        if (isInMemory()) {
            synchronized (stateLock) {
                return new Snapshot(inMemoryEnabled, inMemoryReply,
                        List.copyOf(inMemoryKeywords));
            }
        }
        try {
            return readSnapshot();
        } catch (IOException ignored) {
            return Snapshot.EMPTY;
        }
    }

    /** Called when a prepared auto-reply was actually dispatched. */
    public void noteAutoReplySent() {
        synchronized (stateLock) {
            consecutiveAutoReplies++;
        }
    }

    /** Any human-submitted message re-arms the auto-reply budget. */
    public void noteUserActivity() {
        synchronized (stateLock) {
            consecutiveAutoReplies = 0;
            budgetNoticeShown = false;
        }
    }

    /** The first keyword contained in {@code response} (case-insensitive), or null. */
    public String matchKeyword(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String haystack = response.toLowerCase(Locale.ROOT);
        for (String keyword : effectiveKeywords()) {
            if (!keyword.isEmpty() && haystack.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    // ── Configuration accessors ──────────────────────────────────────────────

    public boolean isEnabled() {
        Snapshot snapshot = snapshotQuietly();
        return snapshot.enabled() == null || snapshot.enabled();
    }

    public String reply() {
        Snapshot snapshot = snapshotQuietly();
        return snapshot.reply() == null || snapshot.reply().isBlank()
                ? DEFAULT_REPLY : snapshot.reply();
    }

    /** Effective keyword list: explicit overrides, otherwise the defaults. */
    public List<String> effectiveKeywords() {
        List<String> stored = snapshotQuietly().keywords();
        return stored.isEmpty() ? List.copyOf(DEFAULT_KEYWORDS) : List.copyOf(stored);
    }

    public void setEnabled(boolean enabled) throws IOException {
        synchronized (stateLock) {
            if (isInMemory()) {
                inMemoryEnabled = enabled;
                return;
            }
            Snapshot snapshot = readSnapshot();
            writeSnapshot(new Snapshot(enabled, snapshot.reply(), snapshot.keywords()));
        }
    }

    public void setReply(String text) throws IOException {
        synchronized (stateLock) {
            String normalized = normalize(text);
            if (normalized.isEmpty()) {
                throw new IOException("Reply text cannot be blank.");
            }
            if (normalized.length() > MAX_REPLY_CHARS) {
                throw new IOException("Reply is too long (maximum "
                        + MAX_REPLY_CHARS + " characters).");
            }
            if (containsUnsafeControl(normalized)) {
                throw new IOException("Reply contains unsupported control characters.");
            }
            if (isInMemory()) {
                inMemoryReply = normalized;
                return;
            }
            Snapshot snapshot = readSnapshot();
            writeSnapshot(new Snapshot(snapshot.enabled(), normalized, snapshot.keywords()));
        }
    }

    public AddSummary addKeywords(String tokens) throws IOException {
        Set<String> requested = tokenize(tokens);
        if (requested.isEmpty()) {
            throw new IOException("No keywords to add.");
        }
        synchronized (stateLock) {
            List<String> current = keywordsForEdit();
            LinkedHashSet<String> valid = new LinkedHashSet<>();
            for (String token : requested) {
                String keyword = normalizeKeyword(token);
                if (keyword.isEmpty() || keyword.length() > MAX_KEYWORD_CHARS
                        || containsUnsafeControl(keyword)) {
                    continue;
                }
                valid.add(keyword);
            }
            if (valid.isEmpty()) {
                throw new IOException("No valid keywords to add.");
            }
            int added = 0;
            for (String keyword : valid) {
                if (current.contains(keyword)) continue;
                if (current.size() >= MAX_KEYWORDS) break;
                current.add(keyword);
                added++;
            }
            persistKeywords(current);
            return new AddSummary(added, valid.size() - added);
        }
    }

    public AddSummary removeKeywords(String tokens) throws IOException {
        Set<String> requested = tokenize(tokens);
        if (requested.isEmpty()) {
            throw new IOException("No keywords to remove.");
        }
        synchronized (stateLock) {
            List<String> current = keywordsForEdit();
            int removed = 0;
            for (String token : requested) {
                if (current.remove(normalizeKeyword(token))) {
                    removed++;
                }
            }
            persistKeywords(current);
            return new AddSummary(removed, 0);
        }
    }

    /** Drop the explicit keyword list so the defaults apply again. */
    public int resetKeywords() throws IOException {
        synchronized (stateLock) {
            int previous = keywordsForEdit().size();
            persistKeywords(new ArrayList<>());
            return previous;
        }
    }

    public record AddSummary(int changed, int skipped) { }

    // ── Slash-command grammar ────────────────────────────────────────────────

    /**
     * Compact grammar: a bare command shows status; {@code on|off} toggles;
     * {@code add|remove <keywords>} edits triggers (comma or space separated);
     * {@code reply <text>} sets the auto-reply; {@code reset} restores default
     * keywords; {@code test <text>} previews a match; any other text is taken
     * as the reply phrase ({@code /continue yes, go ahead}).
     */
    public String handleCommand(String arguments) {
        String input = arguments == null ? "" : arguments.strip();
        if (input.isEmpty()) {
            return status();
        }
        String[] parts = input.split("\\s+", 2);
        String operation = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";

        try {
            return switch (operation) {
                case "status" -> status();
                case "on", "enable" -> {
                    setEnabled(true);
                    yield "Continue auto-reply enabled (project-global; persists across sessions).";
                }
                case "off", "disable" -> {
                    setEnabled(false);
                    yield "Continue auto-reply disabled (project-global; persists across sessions).";
                }
                case "list" -> formatKeywords();
                case "add", "keyword", "keywords" -> {
                    AddSummary summary = addKeywords(rest);
                    yield keywordChangeMessage("Added", summary) + "\n" + formatKeywords();
                }
                case "remove", "rm" -> {
                    AddSummary summary = removeKeywords(rest);
                    yield keywordChangeMessage("Removed", summary) + "\n" + formatKeywords();
                }
                case "reset", "default" -> {
                    int previous = resetKeywords();
                    yield "Restored the " + DEFAULT_KEYWORDS.size() + " default keywords"
                            + " (cleared " + previous + " configured).";
                }
                case "reply" -> rest.isBlank()
                        ? "Auto-reply text: " + reply()
                        : withReply(rest);
                case "test" -> rest.isBlank()
                        ? usage()
                        : describeMatch(rest);
                case "help" -> usage();
                default -> withReply(input);
            };
        } catch (IOException e) {
            return "Could not update /continue settings: " + e.getMessage();
        }
    }

    private String withReply(String text) {
        try {
            setReply(text);
            return "Auto-reply set to: " + reply();
        } catch (IOException e) {
            return "Could not set the auto-reply: " + e.getMessage();
        }
    }

    public String status() {
        List<String> keywords = effectiveKeywords();
        boolean defaults = storedKeywordsQuietly().isEmpty();
        StringBuilder out = new StringBuilder();
        out.append("Continue auto-reply: ")
                .append(isEnabled() ? "on" : "off")
                .append(" (project-global)\n");
        out.append("Reply: ").append(reply()).append('\n');
        synchronized (stateLock) {
            out.append("Consecutive auto-replies: ").append(consecutiveAutoReplies)
                    .append('/').append(MAX_CONSECUTIVE_AUTO_REPLIES).append(" budget\n");
        }
        out.append("Keywords (").append(keywords.size())
                .append(defaults ? ", defaults" : ", customized").append("):");
        for (int i = 0; i < keywords.size(); i++) {
            out.append("\n  ").append(i + 1).append(". ").append(keywords.get(i));
        }
        out.append('\n').append(usage());
        return out.toString();
    }

    public String usage() {
        return "Usage: /continue [on|off] | list | add|remove <keywords> | reply <text> | "
                + "reset | test <text> | <reply text>";
    }

    private String formatKeywords() {
        List<String> keywords = effectiveKeywords();
        boolean defaults = storedKeywordsQuietly().isEmpty();
        if (keywords.isEmpty()) {
            return "No keywords configured.";
        }
        StringBuilder out = new StringBuilder("Trigger keywords (")
                .append(keywords.size())
                .append(defaults ? ", defaults" : ", customized").append("):");
        for (int i = 0; i < keywords.size(); i++) {
            out.append("\n  ").append(i + 1).append(". ").append(keywords.get(i));
        }
        return out.toString();
    }

    private static String keywordChangeMessage(String verb, AddSummary summary) {
        StringBuilder out = new StringBuilder(verb).append(' ')
                .append(summary.changed()).append(" keyword")
                .append(summary.changed() == 1 ? "" : "s").append('.');
        if (summary.skipped() > 0) {
            out.append(" Skipped ").append(summary.skipped())
                    .append(" (blank, duplicate, or invalid).");
        }
        return out.toString();
    }

    private String describeMatch(String text) {
        String matched = matchKeyword(text);
        if (matched == null) {
            return "No keyword match — a turn ending with this text would NOT be auto-replied.";
        }
        return "Matched \"" + matched + "\" — a turn ending with this text WOULD be auto-replied"
                + " with: " + reply();
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    /** Keywords the next edit must build on: stored list, or the active defaults. */
    private List<String> keywordsForEdit() throws IOException {
        if (isInMemory()) {
            synchronized (stateLock) {
                return inMemoryKeywords.isEmpty()
                        ? new ArrayList<>(DEFAULT_KEYWORDS) : new ArrayList<>(inMemoryKeywords);
            }
        }
        List<String> stored = readSnapshot().keywords();
        return stored.isEmpty() ? new ArrayList<>(DEFAULT_KEYWORDS) : stored;
    }

    private List<String> storedKeywordsQuietly() {
        if (isInMemory()) {
            synchronized (stateLock) {
                return List.copyOf(inMemoryKeywords);
            }
        }
        try {
            return readSnapshot().keywords();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private void persistKeywords(List<String> keywords) throws IOException {
        if (isInMemory()) {
            inMemoryKeywords.clear();
            inMemoryKeywords.addAll(keywords);
            return;
        }
        Snapshot snapshot = readSnapshot();
        writeSnapshot(new Snapshot(snapshot.enabled(), snapshot.reply(),
                keywords.stream().map(ContinueManager::normalizeKeyword).distinct().toList()));
    }

    private Snapshot readSnapshot() throws IOException {
        if (Files.isSymbolicLink(projectFile)) {
            throw new IOException("Continue config must not be a symbolic link: " + projectFile);
        }
        if (!Files.isRegularFile(projectFile, LinkOption.NOFOLLOW_LINKS)) {
            return Snapshot.EMPTY;
        }
        if (Files.size(projectFile) > MAX_FILE_BYTES) {
            throw new IOException("Continue config exceeds "
                    + MAX_FILE_BYTES + " bytes: " + projectFile);
        }
        JsonNode root;
        try (InputStream input = Files.newInputStream(projectFile, LinkOption.NOFOLLOW_LINKS)) {
            root = objectMapper.readTree(input);
        }
        if (root == null || !root.isObject()) {
            return Snapshot.EMPTY;
        }
        JsonNode enabledNode = root.path("enabled");
        Boolean enabled = enabledNode.isBoolean() ? enabledNode.asBoolean() : null;
        JsonNode replyNode = root.path("reply");
        String reply = replyNode.isTextual() ? normalize(replyNode.asText()) : null;
        List<String> keywords = new ArrayList<>();
        JsonNode entries = root.path("keywords");
        if (entries.isArray()) {
            if (entries.size() > MAX_KEYWORDS) {
                throw new IOException("Continue config contains more than "
                        + MAX_KEYWORDS + " keywords: " + projectFile);
            }
            for (JsonNode entry : entries) {
                String keyword = normalizeKeyword(entry.asText(""));
                if (!keyword.isEmpty()) {
                    keywords.add(keyword);
                }
            }
        }
        return new Snapshot(enabled, reply, keywords);
    }

    private void writeSnapshot(Snapshot snapshot) throws IOException {
        Path parent = projectFile.getParent();
        if (parent == null) {
            throw new IOException("Continue config has no parent directory: " + projectFile);
        }
        Files.createDirectories(parent);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        if (snapshot.enabled() != null) {
            root.put("enabled", snapshot.enabled());
        }
        if (snapshot.reply() != null) {
            root.put("reply", snapshot.reply());
        }
        var entries = root.putArray("keywords");
        snapshot.keywords().forEach(entries::add);

        withFileLock(projectFile, () -> {
            Path temporary = Files.createTempFile(
                    parent, "." + projectFile.getFileName() + ".", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS)) {
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, root);
                }
                try {
                    Files.move(temporary, projectFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, projectFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return null;
        });
    }

    private boolean isInMemory() {
        return projectFile == null;
    }

    private <T> T withFileLock(Path path, ManagedFileLock.Operation<T> operation)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Object monitor = JVM_FILE_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (monitor) {
            return ManagedFileLock.withLock(normalized, operation);
        }
    }

    // ── Normalization helpers ────────────────────────────────────────────────

    private static Set<String> tokenize(String tokens) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (tokens == null) {
            return result;
        }
        // Commas separate keywords; spaces stay inside a keyword so multi-word
        // phrases ("do you want me to") can be added in one step.
        for (String token : tokens.split(",")) {
            String normalized = normalizeKeyword(token);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeKeyword(String keyword) {
        return normalize(keyword).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    private static boolean containsUnsafeControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((Character.isISOControl(value) && value != '\n' && value != '\t')
                    || value == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
