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

/**
 * Stores session and project reminder lists and prepends them to outbound chat prompts.
 * Session reminders follow a conversation across resume; project reminders are shared by
 * every chat rooted in the same Kompile project folder.
 */
public final class ReminderManager {

    static final String PROMPT_TAG = "kompile_reminders";
    static final String PROJECT_FILE = "chat-reminders.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_REMINDERS = 64;
    private static final int MAX_REMINDER_CHARS = 8_000;
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

    static ReminderManager forStorage(ObjectMapper objectMapper,
                                      Path sessionFile,
                                      Path projectFile) {
        return new ReminderManager(objectMapper,
                sessionFile.toAbsolutePath().normalize(),
                projectFile.toAbsolutePath().normalize(),
                null,
                null);
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
                write(path, reminders);
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
            write(path, List.of());
            return count;
        });
    }

    /**
     * Execute the compact slash-command grammar shared by standard and managed chat.
     * A bare command lists reminders; arbitrary text adds one; add/list/clear are explicit aliases.
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
                    && !operation.equals("add")) {
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
                default -> usage(scope);
            };
        } catch (IOException e) {
            return "Could not update " + scope.displayName() + " reminders: " + e.getMessage();
        }
    }

    /** Return the prompt unchanged when no reminders are configured or storage is unavailable. */
    public String prependTo(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        List<String> project = listQuietly(Scope.PROJECT);
        List<String> session = listQuietly(Scope.SESSION);
        if (project.isEmpty() && session.isEmpty()) {
            return prompt;
        }

        StringBuilder block = new StringBuilder();
        block.append('<').append(PROMPT_TAG).append(">\n")
                .append("The user configured these reminders. Apply them to this prompt:\n");
        int number = 1;
        for (String reminder : project) {
            block.append(number++).append(". [project] ").append(reminder).append('\n');
        }
        for (String reminder : session) {
            block.append(number++).append(". [session] ").append(reminder).append('\n');
        }
        block.append("</").append(PROMPT_TAG).append(">\n\n").append(prompt);
        return block.toString();
    }

    private List<String> listQuietly(Scope scope) {
        try {
            return list(scope);
        } catch (IOException ignored) {
            // One damaged scope must not block chat or hide reminders from the other scope.
            return List.of();
        }
    }

    Path sessionFile() {
        return sessionFile;
    }

    Path projectFile() {
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
                + command + " clear";
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

    int inheritSessionReminders(ReminderManager source) throws IOException {
        int inherited = 0;
        for (String reminder : source.list(Scope.SESSION)) {
            if (add(Scope.SESSION, reminder).added()) {
                inherited++;
            }
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

    private void write(Path path, List<String> reminders) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Reminder file has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
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
