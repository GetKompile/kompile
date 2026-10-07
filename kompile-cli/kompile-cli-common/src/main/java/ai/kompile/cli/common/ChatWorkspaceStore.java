package ai.kompile.cli.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Non-secret desktop workspace index. Chat state/tools still belong to each CLI project/session. */
public final class ChatWorkspaceStore {
    private static final Object MONITOR = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path file;

    /** Non-secret immutable launch selection. Null framework means this folder's saved default. */
    public record Chat(String id, String name, String framework, String model,
                       String nativeSource, String nativeSessionId) {
        public Chat(String id, String name) { this(id, name, null, null); }
        public Chat(String id, String name, String framework, String model) {
            this(id, name, framework, model, null, null);
        }
        public Chat {
            if ((nativeSource == null) != (nativeSessionId == null)
                    || (nativeSource != null && (!nativeSource.matches("[a-z][a-z0-9_-]{0,63}")
                    || nativeSessionId.isBlank() || nativeSessionId.length() > 4096
                    || nativeSessionId.chars().anyMatch(Character::isISOControl))))
                throw new IllegalArgumentException("Invalid native chat reference");
            framework = framework == null || framework.isBlank() ? null : framework.strip().toLowerCase(java.util.Locale.ROOT);
            model = model == null || model.isBlank() ? null : model.strip();
            if (framework != null && !framework.matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("Invalid chat framework");
            if (model != null && (model.length() > 256 || model.startsWith("-")
                    || model.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid chat model");
        }
    }
    public record Project(String id, String name, String workingDirectory, List<Chat> chats) { }
    public record Workspace(int version, List<Project> projects) { }

    public ChatWorkspaceStore() {
        this(KompileHome.homeDirectory().toPath().resolve("chat-workspace.json"));
    }
    public ChatWorkspaceStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

    public Workspace read() throws IOException { return locked(false, workspace -> workspace); }

    public Project register(Path directory) throws IOException {
        Path real = directory.toRealPath();
        if (!Files.isDirectory(real)) throw new IOException("Chat project is not a directory: " + real);
        Workspace updated = locked(true, workspace -> {
            if (workspace.projects().stream().anyMatch(p -> p.workingDirectory().equals(real.toString()))) return workspace;
            if (workspace.projects().size() >= 64) throw new IOException("Workspace project limit (64) reached");
            List<Project> projects = new ArrayList<>(workspace.projects());
            String id = UUID.nameUUIDFromBytes(real.toString().getBytes(StandardCharsets.UTF_8)).toString();
            projects.add(new Project(id, real.getFileName() == null ? real.toString() : real.getFileName().toString(),
                    real.toString(), List.of()));
            return new Workspace(1, List.copyOf(projects));
        });
        return updated.projects().stream().filter(p -> p.workingDirectory().equals(real.toString())).findFirst().orElseThrow();
    }

    /** Create one new folder under an existing parent; never adopt or overwrite an existing target. */
    public Project createProject(Path parent, String name) throws IOException {
        if (parent == null || !parent.isAbsolute())
            throw new IllegalArgumentException("Select an absolute parent directory on this host");
        String folder = name == null ? "" : name.strip();
        if (folder.isEmpty() || folder.length() > 128 || folder.equals(".") || folder.equals("..")
                || folder.contains("/") || folder.contains("\\") || folder.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Project name must be a single folder name (1–128 characters)");
        Path realParent = parent.toRealPath();
        if (!Files.isDirectory(realParent)) throw new IOException("Parent is not a directory: " + realParent);
        Path target = realParent.resolve(folder);
        // Read/validate the index and capacity before creating anything on disk.
        boolean[] created = {false};
        Workspace updated;
        try {
            updated = locked(true, workspace -> {
                if (workspace.projects().size() >= 64) throw new IOException("Workspace project limit (64) reached");
                Files.createDirectory(target);
                created[0] = true;
                Path real = target.toRealPath();
                if (!real.getParent().equals(realParent)) throw new IOException("Project folder escaped its parent");
                List<Project> projects = new ArrayList<>(workspace.projects());
                String id = UUID.nameUUIDFromBytes(real.toString().getBytes(StandardCharsets.UTF_8)).toString();
                projects.add(new Project(id, folder, real.toString(), List.of()));
                return new Workspace(1, List.copyOf(projects));
            });
        } catch (IOException failure) {
            // Never delete a folder another process could already be using. Offer a non-destructive retry.
            if (created[0]) throw new IOException("Created project folder at " + target
                    + " but registration failed. Use Add existing folder to register it after fixing the error: "
                    + failure.getMessage(), failure);
            throw failure;
        }
        return updated.projects().stream().filter(p -> p.workingDirectory().equals(target.toString())).findFirst().orElseThrow();
    }

    public Chat createChat(String projectId, String name) throws IOException {
        return createChat(projectId, name, null, null);
    }

    public Chat createChat(String projectId, String name, String framework, String model) throws IOException {
        return createChat(projectId, name, framework, model, UUID.randomUUID().toString());
    }

    /** Register an identity whose session configuration was validated and pinned before publication. */
    public Chat createChat(String projectId, String name, String framework, String model, String sessionId) throws IOException {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid chat identity");
        String title = name == null || name.isBlank() ? "New Chat" : name.strip();
        if (title.length() > 256 || title.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Chat title must be at most 256 characters without control characters");
        return addChat(projectId, new Chat(sessionId, title, framework, model));
    }

    /** Routing metadata only. The vendor's transcript remains in its original store. */
    public Chat referenceNativeChat(Path directory, String source, String nativeSessionId,
                                    String framework, String title) throws IOException {
        // Validate before registering a directory, and use a stable source/folder-qualified identity.
        if (source == null || nativeSessionId == null || framework == null || framework.isBlank())
            throw new IllegalArgumentException("Native source, session and framework are required");
        new Chat("", "", framework, null, source, nativeSessionId);
        Project project = register(directory);
        String identity = project.workingDirectory() + "\0" + source + "\0" + nativeSessionId;
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String name = title == null || title.isBlank() ? nativeSessionId : title;
        return addChat(project.id(), new Chat(id, name, framework, null, source, nativeSessionId));
    }

    private Chat addChat(String projectId, Chat chat) throws IOException {
        final Chat[] selected = {chat};
        locked(true, workspace -> {
            List<Project> projects = new ArrayList<>(workspace.projects());
            for (int i = 0; i < projects.size(); i++) {
                Project project = projects.get(i);
                if (!project.id().equals(projectId)) continue;
                Path root = Path.of(project.workingDirectory());
                if (!Files.isDirectory(root) || !root.toRealPath().toString().equals(project.workingDirectory()))
                    throw new IOException("Workspace project is no longer available: " + root);
                var existing = project.chats().stream().filter(c -> c.id().equals(chat.id())).findFirst();
                if (existing.isPresent()) { selected[0] = existing.get(); return workspace; }
                if (project.chats().size() >= 128) throw new IOException("Project chat limit (128) reached");
                List<Chat> chats = new ArrayList<>(project.chats());
                chats.add(chat);
                projects.set(i, new Project(project.id(), project.name(), project.workingDirectory(), List.copyOf(chats)));
                return new Workspace(1, List.copyOf(projects));
            }
            throw new IllegalArgumentException("Unknown workspace project: " + projectId);
        });
        return selected[0];
    }

    /** Lookup by canonical folder and browser identity; never take routing from an arbitrary request. */
    public Chat findChat(Path directory, String sessionId) throws IOException {
        String root = directory.toRealPath().toString();
        return read().projects().stream().filter(p -> p.workingDirectory().equals(root))
                .flatMap(p -> p.chats().stream()).filter(c -> c.id().equals(sessionId)).findFirst().orElse(null);
    }

    /** Exact canonical roots only; subdirectories and symlink escapes must be registered separately. */
    public Path resolveRegisteredDirectory(String directory) throws IOException {
        Path real = Path.of(directory).toRealPath();
        if (!Files.isDirectory(real) || read().projects().stream().noneMatch(p -> p.workingDirectory().equals(real.toString())))
            throw new IOException("Chat directory is not a registered workspace project: " + real);
        return real;
    }

    private Workspace locked(boolean save, Update update) throws IOException {
        synchronized (MONITOR) {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
                Workspace workspace = Files.exists(file) ? MAPPER.readValue(file.toFile(), Workspace.class)
                        : new Workspace(1, List.of());
                if (workspace == null || workspace.version() != 1 || workspace.projects() == null
                        || workspace.projects().stream().anyMatch(p -> p == null || p.id() == null
                        || p.name() == null || p.workingDirectory() == null || p.chats() == null
                        || p.chats().stream().anyMatch(c -> c == null || c.id() == null || c.name() == null)))
                    throw new IOException("Invalid chat workspace index: " + file);
                Workspace result = update.apply(workspace);
                if (save) {
                    Path temp = Files.createTempFile(file.getParent(), "chat-workspace-", ".tmp");
                    try {
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), result);
                        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (AtomicMoveNotSupportedException unsupported) {
                            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally { Files.deleteIfExists(temp); }
                }
                return result;
            }
        }
    }
    @FunctionalInterface private interface Update { Workspace apply(Workspace workspace) throws IOException; }
}
