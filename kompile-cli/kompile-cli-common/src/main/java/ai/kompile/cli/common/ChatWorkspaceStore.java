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

    public record Chat(String id, String name) { }
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

    public Chat createChat(String projectId, String name) throws IOException {
        String title = name == null || name.isBlank() ? "New Chat" : name.strip();
        if (title.length() > 256 || title.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Chat title must be at most 256 characters without control characters");
        Chat chat = new Chat(UUID.randomUUID().toString(), title);
        locked(true, workspace -> {
            List<Project> projects = new ArrayList<>(workspace.projects());
            for (int i = 0; i < projects.size(); i++) {
                Project project = projects.get(i);
                if (!project.id().equals(projectId)) continue;
                Path root = Path.of(project.workingDirectory());
                if (!Files.isDirectory(root) || !root.toRealPath().toString().equals(project.workingDirectory()))
                    throw new IOException("Workspace project is no longer available: " + root);
                if (project.chats().size() >= 128) throw new IOException("Project chat limit (128) reached");
                List<Chat> chats = new ArrayList<>(project.chats());
                chats.add(chat);
                projects.set(i, new Project(project.id(), project.name(), project.workingDirectory(), List.copyOf(chats)));
                return new Workspace(1, List.copyOf(projects));
            }
            throw new IllegalArgumentException("Unknown workspace project: " + projectId);
        });
        return chat;
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
