package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
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
        controller = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")),
                new KompileAdapter(temp.resolve("conversations")));
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
        var reopened = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")),
                new KompileAdapter(temp.resolve("conversations"))).workspace();
        assertTrue(reopened.enabled());
        assertEquals(2, reopened.projects().size());
        assertEquals(a, reopened.projects().get(0).chats().get(0));
        assertEquals(b, reopened.projects().get(1).chats().get(0));
    }
    @Test void newProjectIsARegisteredLocalFolderThatCanStartChats() throws Exception {
        var project = controller.newProject(new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new-project"));
        assertEquals(temp.resolve("new-project").toRealPath().toString(), project.workingDirectory());
        assertTrue(project.chats().isEmpty());
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("First"));
        assertEquals(chat, controller.workspace().projects().get(0).chats().get(0));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new-project"))).getStatusCode().value());
        for (String name : new String[] {"../outside", "a/b", ""}) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                    new ChatWorkspaceController.NewProjectRequest(temp.toString(), name))).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.newProject(null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest("relative", "new"))).getStatusCode().value());
    }

    @Test void singleModeCannotExpandItsDirectoryBoundary() throws Exception {
        System.setProperty(WebChatContext.MODE, "single");
        assertFalse(controller.workspace().enabled());
        assertTrue(controller.workspace().projects().isEmpty());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.createChat("unknown", null)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new"))).getStatusCode().value());
        assertFalse(Files.exists(temp.resolve("new")));
    }
    @Test void existingNativeChatsAreListedAndReadWithoutImportPrefixes() throws Exception {
        controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        Path conversations = Files.createDirectory(temp.resolve("conversations"));
        Files.writeString(conversations.resolve("existing-cli.txt"), "CWD: " + temp.toRealPath() + "\n> earlier question\n\n< earlier answer\n");
        Files.writeString(conversations.resolve("subagent-child.txt"), "CWD: " + temp.toRealPath() + "\n> private child\n");
        var registered = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        assertEquals("existing-cli", registered.chats().get(0).id());
        assertEquals("existing-cli", controller.workspace().projects().get(0).chats().get(0).id());
        assertEquals(1, controller.workspace().projects().get(0).chats().size());
        var transcript = controller.transcript(temp.toString(), "existing-cli");
        assertEquals("existing-cli", transcript.sessionId());
        assertEquals(2, transcript.turns().size());
        assertEquals("earlier question", transcript.turns().get(0).content());
        assertEquals("earlier answer", transcript.turns().get(1).content());
        assertTrue(controller.transcript(temp.toString(), "new-uuid").turns().isEmpty());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(temp.toString(), "../escape")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(temp.toString(), "subagent-child")).getStatusCode().value());
        Path other = Files.createDirectory(temp.resolve("different"));
        controller.addProject(new ChatWorkspaceController.ProjectRequest(other.toString()));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(other.toString(), "existing-cli")).getStatusCode().value());
    }

    @Test void symlinkTranscriptDirectoriesBelongToTheirCanonicalProject() throws Exception {
        Path alias = Files.createSymbolicLink(temp.resolveSibling(temp.getFileName() + "-alias"), temp);
        try {
            controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
            Path conversations = Files.createDirectory(temp.resolve("conversations"));
            Files.writeString(conversations.resolve("aliased-cli.txt"), "CWD: " + alias + "\n> aliased question\n");
            Files.writeString(conversations.resolve("unavailable-cli.txt"), "CWD: " + temp.resolve("removed") + "\n> old question\n");
            var chats = controller.workspace().projects().get(0).chats();
            assertEquals(1, chats.size());
            assertEquals("aliased-cli", chats.get(0).id());
            assertEquals("aliased question", controller.transcript(temp.toString(), "aliased-cli").turns().get(0).content());
        } finally { Files.delete(alias); }
    }

    @Test void unavailableProjectDoesNotBreakOtherChats() throws Exception {
        Path offline = Files.createDirectory(temp.resolve("offline"));
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(offline.toString()));
        controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Offline chat"));
        Files.delete(offline);
        controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        assertEquals(2, controller.workspace().projects().size());
        assertEquals("Offline chat", controller.workspace().projects().get(0).chats().get(0).name());
    }

    @Test void legacyWorkspaceAliasAndActualTranscriptAreOneChat() throws Exception {
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Legacy chat"));
        String legacy = KompileAdapter.legacyBrowserSessionId(temp.toRealPath(), chat.id());
        Path conversations = Files.createDirectory(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(legacy + ".txt"), "CWD: " + temp.toRealPath() + "\n> original\n\n< reply\n");
        var chats = controller.workspace().projects().get(0).chats();
        assertEquals(1, chats.size());
        assertEquals(legacy, chats.get(0).id());
        assertEquals(legacy, controller.transcript(temp.toString(), chat.id()).sessionId());
        assertEquals(legacy, controller.transcript(temp.toString(), legacy).sessionId());
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
