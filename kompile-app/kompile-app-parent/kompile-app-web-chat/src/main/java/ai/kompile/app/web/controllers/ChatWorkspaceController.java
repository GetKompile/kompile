package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.common.chat.sources.ChatTurn;
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
    public record View(boolean enabled, String workingDirectory, List<ChatWorkspaceStore.Project> projects) { }
    public record ProjectRequest(String workingDirectory) { }
    public record ChatRequest(String name) { }
    public record NewProjectRequest(String parentDirectory, String name) { }

    public record Transcript(String sessionId, List<ChatTurn> turns) { }

    @Autowired public ChatWorkspaceController() { this(new ChatWorkspaceStore(), new KompileAdapter()); }
    ChatWorkspaceController(ChatWorkspaceStore store) { this(store, new KompileAdapter()); }
    ChatWorkspaceController(ChatWorkspaceStore store, KompileAdapter transcripts) {
        this.store = store;
        this.transcripts = transcripts;
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
                // Resolve old workspace aliases only when their original transcript exists.
                String id = transcripts.resolveSessionId(root, chat.id());
                chats.put(id, new ChatWorkspaceStore.Chat(id, chat.name()));
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
                chats.putIfAbsent(session.sessionId(), new ChatWorkspaceStore.Chat(session.sessionId(), session.title()));
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
        try { return store.createChat(projectId, request == null ? null : request.name()); }
        catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    /** The native CLI transcript, not an imported database copy or a browser history fork. */
    @GetMapping("/transcript")
    public Transcript transcript(@RequestParam String workingDirectory, @RequestParam String sessionId) {
        requireWorkspace();
        try {
            Path directory = store.resolveRegisteredDirectory(workingDirectory);
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
