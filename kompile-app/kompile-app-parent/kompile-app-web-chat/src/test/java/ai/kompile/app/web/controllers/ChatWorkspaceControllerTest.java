package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChatWorkspaceControllerTest {
    @TempDir Path temp;
    String mode, directory;
    ChatWorkspaceController controller;
    @BeforeEach void setup() {
        mode = System.getProperty(WebChatContext.MODE);
        directory = System.getProperty(WebChatContext.WORKING_DIRECTORY);
        System.setProperty(WebChatContext.MODE, "workspace");
        System.setProperty(WebChatContext.WORKING_DIRECTORY, temp.toString());
        controller = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")));
    }
    @AfterEach void restore() {
        restore(WebChatContext.MODE, mode); restore(WebChatContext.WORKING_DIRECTORY, directory);
    }
    static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    @Test void projectAndChatCreationArePersisted() throws Exception {
        var first = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        Path other = Files.createDirectory(temp.resolve("other"));
        var second = controller.addProject(new ChatWorkspaceController.ProjectRequest(other.toString()));
        var a = controller.createChat(first.id(), new ChatWorkspaceController.ChatRequest("A"));
        var b = controller.createChat(second.id(), new ChatWorkspaceController.ChatRequest("B"));
        var reopened = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json"))).workspace();
        assertTrue(reopened.enabled());
        assertEquals(2, reopened.projects().size());
        assertEquals(a, reopened.projects().get(0).chats().get(0));
        assertEquals(b, reopened.projects().get(1).chats().get(0));
    }
    @Test void singleModeCannotExpandItsDirectoryBoundary() throws Exception {
        System.setProperty(WebChatContext.MODE, "single");
        assertFalse(controller.workspace().enabled());
        assertTrue(controller.workspace().projects().isEmpty());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.createChat("unknown", null)).getStatusCode().value());
    }
    @Test void invalidHostDirectoriesAndUnknownProjectsAreRejected() {
        for (String path : new String[] {"relative", temp.resolve("missing").toString(), ""}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> controller.addProject(new ChatWorkspaceController.ProjectRequest(path))).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.createChat("unknown", null)).getStatusCode().value());
    }
}
