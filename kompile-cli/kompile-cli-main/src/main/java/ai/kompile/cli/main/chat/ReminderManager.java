/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.skill.ManagedFileLock;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores session and project reminder lists and prepends them to outbound chat prompts.
 * Session reminders follow a conversation across resume; project reminders are shared by
 * every chat rooted in the same Kompile project folder.
 * <p>
 * Injection pacing is configurable per scope: an interval of {@code n} injects the reminder
 * block on the first user message and then on every {@code n}-th user message of the session;
 * {@code 0} disables injection. Resolution order is session value, then project value, then
 * the {@value #INTERVAL_SYSTEM_PROPERTY} system property, then the default of every message.
 * {@link #decorateUserTurn(String)} is the interval-aware entry point for user prompts and
 * ticks the session turn counter exactly once per prompt; {@link #prependTo(String)} always
 * applies and is idempotent, so it can safely run again on already-decorated text. An automatic
 * resume notice is process-local, bypasses configured reminder intervals, and is consumed once.
 * While the policy judge is active, configured reminders are also exposed as enforceable
 * constraints on every reviewed turn. Intervals greater than one pace prompt repetition only;
 * interval {@code 0} disables both injection and judge enforcement.
 */
public final class ReminderManager {

    static final String PROMPT_TAG = "kompile_reminders";
    static final String PROJECT_FILE = "chat-reminders.json";
    /** System property fallback: inject reminders every n-th user message (0 = never). */
    public static final String INTERVAL_SYSTEM_PROPERTY = "kompile.chat.reminder.interval";
    static final String OPEN_TAG = "<" + PROMPT_TAG + ">";
    static final String CLOSE_TAG = "</" + PROMPT_TAG + ">";
    static final String SESSION_RESUMED_REMINDER =
            "This conversation was resumed in a new Kompile CLI process, possibly after an exit, "
                    + "crash, or restart. Do not assume previously running commands, tools, agents, "
                    + "builds, background work, or other in-memory state survived; verify current "
                    + "state before continuing.";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_REMINDERS = 64;
    private static final int MAX_REMINDER_CHARS = 8_000;
    private static final int MAX_INTERVAL = 10_000;
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final ConcurrentMap<Path, Object> JVM_FILE_LOCKS = new ConcurrentHashMap<>();

    public enum Scope {
        SESSION("session"),
        PROJECT("project-global");

        private final String displayName;

        Scope(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    public record AddResult(boolean added, String message) { }

    private final ObjectMapper objectMapper;
    private final Path sessionFile;
    private final Path projectFile;
    private final List<String> inMemorySession;
    private final List<String> inMemoryProject;
    private final AtomicInteger turnCounter = new AtomicInteger();
    private final AtomicBoolean sessionResumeReminderPending = new AtomicBoolean();
    private Integer inMemoryIntervalSession;
    private Integer inMemoryIntervalProject;

    public ReminderManager(ObjectMapper objectMapper, String sessionId, Path workingDirectory) {
        this(objectMapper,
                sessionFile(sessionId),
                projectFile(workingDirectory),
                null,
                null);
    }

    private ReminderManager(ObjectMapper objectMapper,
                            Path sessionFile,
                            Path projectFile,
                            List<String> inMemorySession,
                            List<String> inMemoryProject) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sessionFile = sessionFile;
        this.projectFile = projectFile;
        this.inMemorySession = inMemorySession;
        this.inMemoryProject = inMemoryProject;
    }

    /** In-memory reminder set for focused runtime tests and embedded callers. */
    public static ReminderManager inMemory(List<String> projectReminders,
                                           List<String> sessionReminders) {
        return new ReminderManager(new ObjectMapper(), null, null,
                normalizedCopy(sessionReminders), normalizedCopy(projectReminders));
    }

    public static ReminderManager forStorage(ObjectMapper objectMapper,
                                             Path sessionFile,
                                             Path projectFile) {
        return new ReminderManager(objectMapper,
                sessionFile.toAbsolutePath().normalize(),
                projectFile.toAbsolutePath().normalize(),
                null,
                null);
    }

    /**
     * Attach restart awareness to the next outbound prompt only. This state is deliberately
     * in-memory: each newly resumed CLI process must issue its own notice, while ordinary
     * configured reminders continue to use their persisted project/session files.
     */
    void scheduleSessionResumeReminder() {
        sessionResumeReminderPending.set(true);
    }

    public List<String> list(Scope scope) throws IOException {
        if (isInMemory()) {
            synchronized (this) {
                return List.copyOf(memoryFor(scope));
            }
        }
        return read(pathFor(scope));
    }

    public AddResult add(Scope scope, String text) throws IOException {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return new AddResult(false, "Reminder text cannot be blank.");
        }
        if (normalized.length() > MAX_REMINDER_CHARS) {
            return new AddResult(false,
                    "Reminder is too long (maximum " + MAX_REMINDER_CHARS + " characters).");
        }
        if (containsUnsafeControl(normalized)) {
            return new AddResult(false, "Reminder contains unsupported control characters.");
        }

        if (isInMemory()) {
            synchronized (this) {
                return addToList(scope, memoryFor(scope), normalized);
            }
        }

        Path path = pathFor(scope);
        return withFileLock(path, () -> {
            List<String> reminders = read(path);
            AddResult result = addToList(scope, reminders, normalized);
            if (result.added()) {
                write(path, reminders, readInterval(path));
            }
            return result;
        });
    }

    public int clear(Scope scope) throws IOException {
        if (isInMemory()) {
            synchronized (this) {
                List<String> reminders = memoryFor(scope);
                int count = reminders.size();
                reminders.clear();
                return count;
            }
        }

        Path path = pathFor(scope);
        return withFileLock(path, () -> {
            int count = read(path).size();
            write(path, List.of(), readInterval(path));
            return count;
        });
    }

    /**
     * Execute the compact slash-command grammar shared by standard and managed chat.
     * A bare command lists reminders; arbitrary text adds one; add/list/clear are explicit
     * aliases; {@code interval <n|every n|off|reset>} configures injection pacing.
     */
    public String handleCommand(Scope scope, String arguments) {
        String input = arguments == null ? "" : arguments.strip();
        String operation;
        String text = "";
        if (input.isEmpty()) {
            operation = "list";
        } else {
            String[] parts = input.split("\\s+", 2);
            operation = parts[0].toLowerCase(Locale.ROOT);
            if (parts.length > 1) {
                text = parts[1].strip();
            }
            if (!operation.equals("list") && !operation.equals("clear")
                    && !operation.equals("add") && !operation.equals("interval")) {
                operation = "add";
                text = input;
            }
        }

        try {
            return switch (operation) {
                case "list" -> formatList(scope, list(scope));
                case "clear" -> {
                    int count = clear(scope);
                    yield "Cleared " + count + " " + scope.displayName() + " reminder"
                            + (count == 1 ? "." : "s.");
                }
                case "add" -> {
                    if (text.isBlank()) {
                        yield usage(scope);
                    }
                    yield add(scope, text).message();
                }
                case "interval" -> handleIntervalCommand(scope, text);
                default -> usage(scope);
            };
        } catch (IOException e) {
            return "Could not update " + scope.displayName() + " reminders: " + e.getMessage();
        }
    }

    /** Return the prompt unchanged when no reminders are configured or storage is unavailable. */
    public String prependTo(String prompt) {
        return prependTo(prompt, true, false);
    }

    private String prependTo(String prompt, boolean includeConfigured,
                             boolean includeSessionResumeReminder) {
        if (prompt == null || prompt.isBlank() || prompt.contains(OPEN_TAG)) {
            // Blank, or already carrying a reminder block: never stack a second one.
            return prompt;
        }
        List<String> project = includeConfigured ? listQuietly(Scope.PROJECT) : List.of();
        List<String> session = includeConfigured ? listQuietly(Scope.SESSION) : List.of();
        if (!includeSessionResumeReminder && project.isEmpty() && session.isEmpty()) {
            return prompt;
        }

        StringBuilder block = new StringBuilder();
        block.append(OPEN_TAG).append('\n');
        if (!includeSessionResumeReminder) {
            block.append("The user configured these reminders. Apply them to this prompt:\n");
        } else {
            block.append("Kompile added an automatic session reminder. Apply it and any configured "
                    + "reminders to this prompt:\n");
        }
        int number = 1;
        if (includeSessionResumeReminder) {
            block.append(number++).append(". [system] ")
                    .append(SESSION_RESUMED_REMINDER).append('\n');
        }
        for (String reminder : project) {
            block.append(number++).append(". [project] ").append(reminder).append('\n');
        }
        for (String reminder : session) {
            block.append(number++).append(". [session] ").append(reminder).append('\n');
        }
        block.append(CLOSE_TAG).append("\n\n").append(stripReminderBlock(prompt));
        return block.toString();
    }

    /**
     * Interval-aware decoration for one user prompt. Ticks the session turn counter exactly
     * once and prepends the reminder block only when the configured interval says this turn
     * is due: the first prompt, then every {@code n}-th prompt ({@code n = interval});
     * {@code interval 0} suppresses configured reminders. A pending automatic resume notice
     * still applies once. Already-decorated input is returned untouched so a dispatcher and
     * its downstream consumer can both call this safely.
     */
    public String decorateUserTurn(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.contains(OPEN_TAG)) {
            return prompt;
        }
        boolean includeConfigured = configuredReminderDue(true);
        boolean includeSessionResumeReminder = sessionResumeReminderPending.getAndSet(false);
        if (!includeConfigured && !includeSessionResumeReminder) {
            return prompt;
        }
        return prependTo(prompt, includeConfigured, includeSessionResumeReminder);
    }

    /**
     * What {@link #decorateUserTurn} will return for this prompt if it is the next user
     * turn, without advancing the interval counter. Transcript writers use this to record
     * the exact outbound text while the send boundary performs the one real tick.
     */
    public String previewUserTurn(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.contains(OPEN_TAG)) {
            return prompt;
        }
        boolean includeConfigured = configuredReminderDue(false);
        boolean includeSessionResumeReminder = sessionResumeReminderPending.get();
        if (!includeConfigured && !includeSessionResumeReminder) {
            return prompt;
        }
        return prependTo(prompt, includeConfigured, includeSessionResumeReminder);
    }

    private boolean configuredReminderDue(boolean advanceTurn) {
        int interval = effectiveInterval();
        if (interval == 0) {
            return false;
        }
        if (interval == 1) {
            return true;
        }
        int turn = advanceTurn ? turnCounter.incrementAndGet() - 1 : turnCounter.get();
        return turn % interval == 0;
    }

    /**
     * Removes a leading reminder block from a decorated prompt, returning the real
     * user content. Title derivation and cross-agent export use this so stored
     * metadata reflects what the user asked, not the injected reminder wrapper.
     */
    public static String stripReminderBlock(String prompt) {
        String result = prompt;
        while (result.startsWith(OPEN_TAG)) {
            int end = result.indexOf(CLOSE_TAG);
            if (end < 0) {
                return result; // Unterminated block — leave the prompt untouched.
            }
            result = result.substring(end + CLOSE_TAG.length()).stripLeading();
        }
        return result;
    }

    /** True when line content opens a reminder block (the {@code <kompile_reminders>} open tag). */
    public static boolean opensReminderBlock(String content) {
        return content != null && content.stripLeading().startsWith(OPEN_TAG);
    }

    /** True when line content closes a reminder block (the {@code </kompile_reminders>} close tag). */
    public static boolean closesReminderBlock(String content) {
        return content != null && content.stripLeading().startsWith(CLOSE_TAG);
    }

    /**
     * Body of the injected reminder block inside a decorated prompt (the numbered
     * reminder lines plus their scope tags), or {@code null} when the prompt carries
     * no reminder block. Terminal renderers use this to show the user exactly what
     * was attached to an outbound agent prompt.
     */
    public static String reminderBlockContent(String prompt) {
        if (prompt == null) {
            return null;
        }
        int open = prompt.indexOf(OPEN_TAG);
        if (open < 0) {
            return null;
        }
        int bodyStart = open + OPEN_TAG.length();
        int close = prompt.indexOf(CLOSE_TAG, bodyStart);
        if (close < 0) {
            return null;
        }
        String body = prompt.substring(bodyStart, close).strip();
        return body.isEmpty() ? null : body;
    }

    /**
     * Return the currently configured reminders in the same scoped order used for prompt
     * injection, formatted for the policy judge. Unlike {@link #decorateUserTurn(String)}, this
     * does not advance the turn counter: a non-zero interval keeps reminders enforceable between
     * repeated prompt injections. Interval {@code 0} is the explicit opt-out for both behaviors.
     */
    public String enforcementConstraints() {
        if (effectiveInterval() == 0) {
            return "";
        }
        List<String> project = listQuietly(Scope.PROJECT);
        List<String> session = listQuietly(Scope.SESSION);
        if (project.isEmpty() && session.isEmpty()) {
            return "";
        }

        StringBuilder constraints = new StringBuilder();
        int number = 1;
        for (String reminder : project) {
            constraints.append(number++).append(". [project] ").append(reminder).append('\n');
        }
        for (String reminder : session) {
            constraints.append(number++).append(". [session] ").append(reminder).append('\n');
        }
        return constraints.toString().stripTrailing();
    }

    private int effectiveInterval() {
        Integer session = storedInterval(Scope.SESSION);
        if (session != null) {
            return session;
        }
        Integer project = storedInterval(Scope.PROJECT);
        if (project != null) {
            return project;
        }
        return systemPropertyInterval();
    }

    private Integer storedInterval(Scope scope) {
        if (isInMemory()) {
            synchronized (this) {
                return scope == Scope.SESSION ? inMemoryIntervalSession : inMemoryIntervalProject;
            }
        }
        try {
            return readInterval(pathFor(scope));
        } catch (IOException ignored) {
            return null; // One damaged scope must not block chat.
        }
    }

    private static int systemPropertyInterval() {
        String raw = System.getProperty(INTERVAL_SYSTEM_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return clampInterval(Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int clampInterval(int value) {
        return Math.max(0, Math.min(MAX_INTERVAL, value));
    }

    private String handleIntervalCommand(Scope scope, String text) throws IOException {
        if (text.isBlank()) {
            return describeInterval(scope);
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("every ")) {
            normalized = normalized.substring("every ".length()).strip();
        }
        Integer value;
        switch (normalized) {
            case "off", "never", "0" -> value = 0;
            case "reset", "default" -> value = null;
            case "on", "each" -> value = 1;
            default -> {
                try {
                    value = clampInterval(Integer.parseInt(normalized));
                } catch (NumberFormatException e) {
                    return intervalUsage(scope);
                }
            }
        }
        return setInterval(scope, value);
    }

    private String describeInterval(Scope scope) {
        Integer session = storedInterval(Scope.SESSION);
        Integer project = storedInterval(Scope.PROJECT);
        int effective = effectiveInterval();
        StringBuilder description = new StringBuilder("Reminder interval: ");
        description.append(effective == 0 ? "off (never injected)"
                : "every " + effective + " user message" + (effective == 1 ? "" : "s"));
        description.append(" [source: ");
        if (session != null) {
            description.append("session");
        } else if (project != null) {
            description.append("project");
        } else {
            description.append(System.getProperty(INTERVAL_SYSTEM_PROPERTY) != null
                    ? "system property" : "default");
        }
        description.append(']');
        description.append("\nStored session interval: ").append(session == null ? "(unset)" : session);
        description.append("\nStored project interval: ").append(project == null ? "(unset)" : project);
        description.append('\n').append(intervalUsage(scope));
        return description.toString();
    }

    private String setInterval(Scope scope, Integer value) throws IOException {
        if (isInMemory()) {
            synchronized (this) {
                if (scope == Scope.SESSION) {
                    inMemoryIntervalSession = value;
                } else {
                    inMemoryIntervalProject = value;
                }
            }
        } else {
            Path path = pathFor(scope);
            withFileLock(path, () -> {
                write(path, read(path), value);
                return null;
            });
        }
        if (value == null) {
            return "Removed " + scope.displayName() + " interval; it now falls back to the "
                    + (scope == Scope.SESSION ? "project or default" : "default") + " value.";
        }
        if (value == 0) {
            return "Reminders will not be injected until the interval is changed.";
        }
        return "Reminders will be injected every " + value + " user message"
                + (value == 1 ? "" : "s") + " (first message included).";
    }

    private List<String> listQuietly(Scope scope) {
        try {
            return list(scope);
        } catch (IOException ignored) {
            // One damaged scope must not block chat or hide reminders from the other scope.
            return List.of();
        }
    }

    /** Exact session reminder storage used by managed judge subprocesses. */
    public Path sessionFile() {
        return sessionFile;
    }

    /** Exact project reminder storage used by managed judge subprocesses. */
    public Path projectFile() {
        return projectFile;
    }

    private AddResult addToList(Scope scope, List<String> reminders, String normalized) {
        if (reminders.contains(normalized)) {
            return new AddResult(false,
                    "That " + scope.displayName() + " reminder is already configured.");
        }
        if (reminders.size() >= MAX_REMINDERS) {
            return new AddResult(false,
                    "Cannot add more than " + MAX_REMINDERS + " "
                            + scope.displayName() + " reminders.");
        }
        reminders.add(normalized);
        return new AddResult(true, "Added " + scope.displayName() + " reminder.");
    }

    private static String formatList(Scope scope, List<String> reminders) {
        if (reminders.isEmpty()) {
            return "No " + scope.displayName() + " reminders configured.";
        }
        StringBuilder output = new StringBuilder();
        output.append(Character.toUpperCase(scope.displayName().charAt(0)))
                .append(scope.displayName().substring(1))
                .append(" reminders (").append(reminders.size()).append("):");
        for (int i = 0; i < reminders.size(); i++) {
            output.append("\n  ").append(i + 1).append(". ")
                    .append(escapeForDisplay(reminders.get(i)));
        }
        return output.toString();
    }

    private static String usage(Scope scope) {
        String command = scope == Scope.SESSION ? "/reminder" : "/reminder-global";
        return "Usage: " + command + " <text> | " + command + " list | "
                + command + " clear | " + command + " interval <n|every n|off|reset>";
    }

    private static String intervalUsage(Scope scope) {
        String command = scope == Scope.SESSION ? "/reminder" : "/reminder-global";
        return "Usage: " + command + " interval <n|every n|off|reset> "
                + "(n = inject every n-th user message; off = never; reset = fall back)";
    }

    private List<String> read(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Reminder file must not be a symbolic link: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new ArrayList<>();
        }
        long fileSize = Files.size(path);
        if (fileSize > MAX_FILE_BYTES) {
            throw new IOException("Reminder file exceeds " + MAX_FILE_BYTES + " bytes: " + path);
        }
        JsonNode root;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            root = objectMapper.readTree(input);
        }
        if (root == null || !root.isObject()) {
            return new ArrayList<>();
        }
        JsonNode entries = root.path("reminders");
        if (!entries.isArray()) {
            return new ArrayList<>();
        }
        if (entries.size() > MAX_REMINDERS) {
            throw new IOException("Reminder file contains more than " + MAX_REMINDERS
                    + " entries: " + path);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode entry : entries) {
            String normalized = normalize(entry.asText(""));
            if (normalized.length() > MAX_REMINDER_CHARS) {
                throw new IOException("Reminder exceeds " + MAX_REMINDER_CHARS
                        + " characters in " + path);
            }
            if (containsUnsafeControl(normalized)) {
                throw new IOException("Reminder contains unsupported control characters in " + path);
            }
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    /** Tolerant interval lookup: {@code null} when absent, unreadable, or out of range. */
    private Integer readInterval(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        JsonNode root;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            root = objectMapper.readTree(input);
        }
        JsonNode interval = root == null ? null : root.path("interval");
        if (interval == null || !interval.isInt()) {
            return null;
        }
        int value = interval.asInt();
        return value < 0 || value > MAX_INTERVAL ? null : value;
    }

    int inheritSessionReminders(ReminderManager source) throws IOException {
        int inherited = 0;
        for (String reminder : source.list(Scope.SESSION)) {
            if (add(Scope.SESSION, reminder).added()) {
                inherited++;
            }
        }
        Integer interval = source.storedInterval(Scope.SESSION);
        if (interval != null) {
            setInterval(Scope.SESSION, interval);
        }
        return inherited;
    }

    private <T> T withFileLock(Path path, ManagedFileLock.Operation<T> operation)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Object monitor = JVM_FILE_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (monitor) {
            return ManagedFileLock.withLock(normalized, operation);
        }
    }

    private void write(Path path, List<String> reminders, Integer interval) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Reminder file has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        if (interval != null) {
            root.put("interval", interval);
        }
        ArrayNode entries = root.putArray("reminders");
        reminders.forEach(entries::add);

        Path temporary = Files.createTempFile(
                parent, "." + path.getFileName() + ".", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, root);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean isInMemory() {
        return sessionFile == null;
    }

    private List<String> memoryFor(Scope scope) {
        return scope == Scope.SESSION ? inMemorySession : inMemoryProject;
    }

    private Path pathFor(Scope scope) {
        return scope == Scope.SESSION ? sessionFile : projectFile;
    }

    private static Path sessionFile(String sessionId) {
        String safeId = sessionId == null || sessionId.isBlank()
                ? "unknown-session"
                : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve(safeId + ".reminders.json")
                .toAbsolutePath().normalize();
    }

    private static Path projectFile(Path workingDirectory) {
        Path working = (workingDirectory == null ? Path.of(".") : workingDirectory)
                .toAbsolutePath().normalize();
        Path root = new KompileProjectStore().findProjectRoot(working).orElse(working);
        return root.resolve(KompileProjectStore.METADATA_DIR)
                .resolve(PROJECT_FILE)
                .toAbsolutePath().normalize();
    }

    private static List<String> normalizedCopy(List<String> reminders) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (reminders != null) {
            for (String reminder : reminders) {
                String normalized = normalize(reminder);
                if (!normalized.isEmpty()) {
                    unique.add(normalized);
                }
            }
        }
        return new ArrayList<>(unique);
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

    private static String escapeForDisplay(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\n') {
                escaped.append("\\n");
            } else if (value == '\t') {
                escaped.append("\\t");
            } else if (Character.isISOControl(value) || value == 0x7f) {
                escaped.append(String.format("\\u%04x", (int) value));
            } else {
                escaped.append(value);
            }
        }
        return escaped.toString();
    }
}
