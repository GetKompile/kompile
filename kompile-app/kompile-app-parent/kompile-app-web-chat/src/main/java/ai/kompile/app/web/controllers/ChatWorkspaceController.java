package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** CLI project selection only. Never changes the Spring process's global project identity. */
@RestController
@RequestMapping("/api/agents/chat/workspace")
public class ChatWorkspaceController {
    private final ChatWorkspaceStore store;
    private final KompileAdapter transcripts;
    private final ChatSourceRegistry sources;
    @Autowired private ai.kompile.app.services.agent.ChatHarnessClient harness;
    public record SetupRequest(String name, com.fasterxml.jackson.databind.JsonNode selection) { }
    private static final java.util.Map<String, String> NATIVE_FRAMEWORKS = ChatWorkspaceStore.NATIVE_FRAMEWORKS;
    public record NativeFolder(String source, String name, List<ChatSessionSummary> chats, String error) { }
    public record NativeRequest(String source, String sessionId) { }
    public record NativeSelection(ChatWorkspaceStore.Project project, ChatWorkspaceStore.Chat chat) { }
    public record View(boolean enabled, String workingDirectory, List<ChatWorkspaceStore.Project> projects) { }
    public record ProjectRequest(String workingDirectory) { }
    public record ChatRequest(String name, String framework, String model) {
        public ChatRequest(String name) { this(name, null, null); }
    }
    public record NewProjectRequest(String parentDirectory, String name) { }

    public record Transcript(String sessionId, List<ChatTurn> turns) { }

    @Autowired public ChatWorkspaceController() { this(new ChatWorkspaceStore(), new KompileAdapter()); }
    ChatWorkspaceController(ChatWorkspaceStore store) { this(store, new KompileAdapter()); }
    ChatWorkspaceController(ChatWorkspaceStore store, KompileAdapter transcripts) {
        this(store, transcripts, ChatSourceRegistry.getInstance());
    }
    ChatWorkspaceController(ChatWorkspaceStore store, KompileAdapter transcripts, ChatSourceRegistry sources) {
        this.store = store;
        this.transcripts = transcripts;
        this.sources = sources;
    }

    public record NativeSource(String source, String name) { }

    /** Cheap catalog: show folders before any vendor transcript scan completes. */
    @GetMapping("/native-sources") public List<NativeSource> nativeSources() {
        requireWorkspace();
        return sources.all().stream().filter(a -> NATIVE_FRAMEWORKS.containsKey(a.id()))
                .map(a -> new NativeSource(a.id(), a.displayName())).toList();
    }

    /** Each vendor is requested independently, so slow stores cannot block other folders. */
    @GetMapping("/projects/{projectId}/native-folders/{source}")
    public NativeFolder projectNativeFolder(@PathVariable String projectId, @PathVariable String source) {
        requireWorkspace();
        final ChatSourceAdapter adapter;
        final Path directory;
        try {
            adapter = nativeAdapter(source);
            var project = store.read().projects().stream().filter(p -> p.id().equals(projectId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
            directory = store.resolveRegisteredDirectory(project.workingDirectory());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        try {
            var sessions = adapter.list(directory).stream().filter(s -> belongsToProject(adapter, s, directory)).toList();
            return new NativeFolder(adapter.id(), adapter.displayName(), sessions, null);
        } catch (IOException | RuntimeException unavailable) {
            String message = unavailable.getMessage();
            return new NativeFolder(adapter.id(), adapter.displayName(), List.of(),
                    message == null || message.isBlank() ? "Cannot read vendor chats" : message);
        }
    }

    private static boolean belongsToProject(ChatSourceAdapter adapter, ChatSessionSummary session, Path directory) {
        if (session.sessionId().startsWith("subagent-")) return false;
        try {
            Path cwd = session.workingDirectory() == null || session.workingDirectory().isBlank()
                    ? adapter.resolveWorkingDirectory(session.sessionId()).orElse(null)
                    : Path.of(session.workingDirectory());
            return cwd != null && cwd.isAbsolute() && cwd.toRealPath().equals(directory);
        } catch (IOException | IllegalArgumentException unavailable) {
            // Removed folders and malformed paths in one transcript must not hide the others.
            return false;
        }
    }

    /** Reuse resume's native readers; browsing never imports or rewrites a transcript. */
    @GetMapping("/native-folders") public List<NativeFolder> nativeFolders() {
        requireWorkspace();
        List<NativeFolder> folders = new ArrayList<>();
        for (var adapter : sources.all()) {
            if (!NATIVE_FRAMEWORKS.containsKey(adapter.id())) continue;
            try {
                var sessions = adapter.list().stream()
                        .filter(s -> !s.sessionId().startsWith("subagent-")).toList();
                folders.add(new NativeFolder(adapter.id(), adapter.displayName(), sessions, null));
            } catch (IOException | RuntimeException unavailable) {
                folders.add(new NativeFolder(adapter.id(), adapter.displayName(), List.of(), unavailable.getMessage()));
            }
        }
        return List.copyOf(folders);
    }

    @PostMapping("/native/open") public NativeSelection openNative(@RequestBody NativeRequest request) {
        requireWorkspace();
        try {
            if (request == null || request.sessionId() == null || request.sessionId().isBlank())
                throw new IllegalArgumentException("Select a native chat session");
            var adapter = nativeAdapter(request.source());
            // Especially important for path-valued native IDs: only open sessions the reader lists.
            var session = adapter.list().stream().filter(s -> s.sessionId().equals(request.sessionId())
                    && !s.sessionId().startsWith("subagent-")).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown native chat session"));
            Path directory = adapter.resolveWorkingDirectory(session.sessionId())
                    .orElseThrow(() -> new IllegalArgumentException("Native session has no working directory")).toRealPath();
            // Opens in Kompile chat, which carries the vendor's turns over on its first turn; choosing the
            // vendor's framework in the model menu resumes the original vendor session instead.
            var chat = inKompileChat(directory, store.referenceNativeChat(directory, adapter.id(), session.sessionId(),
                    "standard", session.title()));
            var project = store.read().projects().stream()
                    .filter(p -> p.workingDirectory().equals(directory.toString())).findFirst().orElseThrow();
            return new NativeSelection(project, chat);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    /** A vendor chat recorded before Kompile chat was the default starts there too, until it takes a turn. */
    private ChatWorkspaceStore.Chat inKompileChat(Path directory, ChatWorkspaceStore.Chat chat) throws IOException {
        if (chat.nativeSource() == null
                || !java.util.Objects.equals(chat.framework(), ChatWorkspaceStore.nativeFramework(chat.nativeSource()))
                || !transcripts.readTurns(chat.id()).isEmpty()
                || !store.startInKompileChat(directory, chat.id())) return chat;
        var updated = store.findChat(directory, chat.id());
        return updated == null ? chat : updated;
    }

    private ChatSourceAdapter nativeAdapter(String source) {
        if (source == null || !NATIVE_FRAMEWORKS.containsKey(source))
            throw new IllegalArgumentException("Unsupported native chat source");
        return sources.require(source);
    }

    @GetMapping public View workspace() throws IOException {
        Path directory = WebChatContext.workingDirectory();
        boolean enabled = WebChatContext.workspace() && directory != null;
        if (!enabled) return new View(false, directory == null ? null : directory.toString(), List.of());
        var sessions = transcripts.list();
        List<ChatWorkspaceStore.Project> projects = new ArrayList<>();
        for (var project : store.read().projects()) {
            Path root = Path.of(project.workingDirectory());
            // An offline/removed project must not hide every other project in the workspace.
            if (!java.nio.file.Files.isDirectory(root) || !root.toRealPath().equals(root)) {
                projects.add(project);
                continue;
            }
            var chats = new LinkedHashMap<String, ChatWorkspaceStore.Chat>();
            for (var chat : project.chats()) {
                if (chat.nativeSource() != null) { chats.put(chat.id(), inKompileChat(root, chat)); continue; }
                // Resolve old workspace aliases only when their original transcript exists.
                String id = transcripts.resolveSessionId(root, chat.id());
                String title = transcripts.readTitle(id);
                chats.put(id, new ChatWorkspaceStore.Chat(id, "(untitled)".equals(title) ? chat.name() : title,
                        chat.framework(), chat.model(), null, null, chat.route()));
            }
            for (var session : sessions) {
                if (session.sessionId().startsWith("subagent-") || session.workingDirectory() == null
                        || session.messageCount() == 0) continue;
                try {
                    if (!Path.of(session.workingDirectory()).toRealPath().equals(root)) continue;
                } catch (IOException | IllegalArgumentException unavailable) {
                    // A removed folder or broken symlink in one transcript must not hide the others.
                    continue;
                }
                if (!"(untitled)".equals(session.title()) || !chats.containsKey(session.sessionId())) {
                    var existing = chats.get(session.sessionId());
                    if (existing != null && existing.nativeSource() != null) continue;
                    chats.put(session.sessionId(), new ChatWorkspaceStore.Chat(session.sessionId(), session.title(),
                            existing == null ? null : existing.framework(), existing == null ? null : existing.model(),
                            null, null, existing == null ? null : existing.route()));
                }
            }
            projects.add(new ChatWorkspaceStore.Project(project.id(), project.name(), project.workingDirectory(), List.copyOf(chats.values())));
        }
        return new View(true, directory.toString(), List.copyOf(projects));
    }

    @PostMapping("/projects") public ChatWorkspaceStore.Project addProject(@RequestBody ProjectRequest request) {
        requireWorkspace();
        try {
            String directory = request == null ? null : request.workingDirectory();
            if (directory == null || directory.isBlank() || directory.length() > 4096 || !Path.of(directory).isAbsolute())
                throw new IllegalArgumentException("Select an absolute directory on the host running Kompile");
            var project = store.register(Path.of(directory));
            return workspace().projects().stream().filter(p -> p.id().equals(project.id())).findFirst().orElseThrow();
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PostMapping("/projects/new")
    public ChatWorkspaceStore.Project newProject(@RequestBody NewProjectRequest request) {
        requireWorkspace();
        try {
            String parent = request == null ? null : request.parentDirectory();
            if (parent == null || parent.isBlank() || parent.length() > 4096)
                throw new IllegalArgumentException("Select an absolute parent directory on the host running Kompile");
            return store.createProject(Path.of(parent), request.name());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PostMapping("/projects/{projectId}/chats")
    public ChatWorkspaceStore.Chat createChat(@PathVariable String projectId, @RequestBody ChatRequest request) {
        requireWorkspace();
        try { return store.createChat(projectId, request == null ? null : request.name(),
                request == null ? null : request.framework(), request == null ? null : request.model()); }
        catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PostMapping("/projects/{projectId}/chat-setup/options")
    public com.fasterxml.jackson.databind.JsonNode setupOptions(@PathVariable String projectId,
                                                               @RequestBody(required = false) SetupRequest request) {
        requireWorkspace();
        try {
            Path directory = setupDirectory(projectId);
            var payload = ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("action", "catalog");
            if (request != null && request.selection() != null) payload.set("selection", request.selection());
            return harness.setupChat(directory.toString(), payload);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid chat setup project", invalid);
        }
    }

    @PostMapping("/projects/{projectId}/chat-setup/create")
    public ChatWorkspaceStore.Chat setupCreate(@PathVariable String projectId, @RequestBody SetupRequest request) {
        requireWorkspace();
        try {
            Path directory = setupDirectory(projectId);
            if (request == null || request.selection() == null || !request.selection().isObject())
                throw new IllegalArgumentException("Choose chat setup options");
            String title = request.name() == null || request.name().isBlank() ? "New Chat" : request.name().strip();
            if (title.length() > 256 || title.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid chat title");
            String id = java.util.UUID.randomUUID().toString();
            var payload = ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode()
                    .put("action", "create").put("sessionId", id);
            payload.set("selection", request.selection());
            var result = harness.setupChat(directory.toString(), payload);
            if (!result.path("ok").asBoolean(false))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        result.path("status").asText("Chat setup failed; no chat was created"));
            return store.createChat(projectId, title, result.path("framework").asText("standard"),
                    result.path("model").asText(null), id);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create configured chat", invalid);
        }
    }

    private Path setupDirectory(String projectId) throws IOException {
        var project = store.read().projects().stream().filter(p -> p.id().equals(projectId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
        return store.resolveRegisteredDirectory(project.workingDirectory());
    }

    @PutMapping("/projects/{projectId}/chats/{sessionId}/title")
    public ChatWorkspaceStore.Chat renameChat(@PathVariable String projectId, @PathVariable String sessionId,
                                             @RequestBody ChatRequest request) {
        requireWorkspace();
        try {
            var project = workspace().projects().stream().filter(p -> p.id().equals(projectId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown project"));
            var chat = project.chats().stream().filter(c -> c.id().equals(sessionId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown chat in this project"));
            if (chat.nativeSource() != null) throw new IllegalArgumentException("Native transcripts are kept as-is; rename in the vendor framework");
            String title = transcripts.rename(store.resolveRegisteredDirectory(project.workingDirectory()), chat.id(),
                    request == null ? null : request.name());
            return new ChatWorkspaceStore.Chat(chat.id(), title, chat.framework(), chat.model(), null, null, chat.route());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    /** The native CLI transcript, not an imported database copy or a browser history fork. */
    @GetMapping("/transcript")
    public Transcript transcript(@RequestParam String workingDirectory, @RequestParam String sessionId) {
        requireWorkspace();
        try {
            Path directory = store.resolveRegisteredDirectory(workingDirectory);
            var reference = store.findChat(directory, sessionId);
            if (reference != null && reference.nativeSource() != null) {
                // Once its turns were carried over, the chat's own transcript holds them and every turn since.
                if (transcripts.carriesOver(reference.id(), reference.nativeSource(), reference.nativeSessionId()))
                    return new Transcript(reference.id(), transcripts.readTurns(reference.id()));
                var adapter = nativeAdapter(reference.nativeSource());
                Path nativeDirectory = adapter.resolveWorkingDirectory(reference.nativeSessionId())
                        .orElseThrow(() -> new IllegalArgumentException("Native session is no longer available")).toRealPath();
                if (!nativeDirectory.equals(directory)) throw new IllegalArgumentException("Native session belongs to another folder");
                List<ChatTurn> turns = adapter.readTurns(reference.nativeSessionId());
                // A chat that took turns in Kompile before carry-over existed holds them only in its own transcript.
                List<ChatTurn> own = transcripts.carriesOverAny(reference.id()) ? List.of() : transcripts.readTurns(reference.id());
                if (own.isEmpty()) return new Transcript(reference.id(), turns);
                List<ChatTurn> merged = new ArrayList<>(turns);
                merged.add(new ChatTurn("system", "Turns taken in Kompile chat:"));
                merged.addAll(own);
                return new Transcript(reference.id(), merged);
            }
            String id = transcripts.resolveSessionId(directory, sessionId);
            if (id.startsWith("subagent-")) throw new IllegalArgumentException("Select a top-level chat session");
            return new Transcript(id, transcripts.readTurns(id));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private static void requireWorkspace() {
        try {
            if (WebChatContext.workspace() && WebChatContext.workingDirectory() != null) return;
        } catch (IOException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, invalid.getMessage(), invalid);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Launch with kompile chat --web to manage folder-based projects");
    }
}
