package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
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
    public record View(boolean enabled, String workingDirectory, List<ChatWorkspaceStore.Project> projects) { }
    public record ProjectRequest(String workingDirectory) { }
    public record ChatRequest(String name) { }

    @Autowired public ChatWorkspaceController() { this(new ChatWorkspaceStore()); }
    ChatWorkspaceController(ChatWorkspaceStore store) { this.store = store; }

    @GetMapping public View workspace() throws IOException {
        Path directory = WebChatContext.workingDirectory();
        boolean enabled = WebChatContext.workspace() && directory != null;
        return new View(enabled, directory == null ? null : directory.toString(),
                enabled ? store.read().projects() : List.of());
    }

    @PostMapping("/projects") public ChatWorkspaceStore.Project addProject(@RequestBody ProjectRequest request) {
        requireWorkspace();
        try {
            String directory = request == null ? null : request.workingDirectory();
            if (directory == null || directory.isBlank() || directory.length() > 4096 || !Path.of(directory).isAbsolute())
                throw new IllegalArgumentException("Select an absolute directory on the host running Kompile");
            return store.register(Path.of(directory));
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

    private static void requireWorkspace() {
        try {
            if (WebChatContext.workspace() && WebChatContext.workingDirectory() != null) return;
        } catch (IOException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, invalid.getMessage(), invalid);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Launch with kompile chat --web --workspace to manage projects");
    }
}
