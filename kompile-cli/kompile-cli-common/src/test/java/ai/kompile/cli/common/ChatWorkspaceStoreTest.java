package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ChatWorkspaceStoreTest {
    @TempDir Path temp;
    private ChatWorkspaceStore store() { return new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")); }

    @Test void projectsAndIndependentChatsSurviveReopening() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        var project = store().register(first);
        assertEquals(project.id(), store().register(first.resolve("." )).id());
        var other = store().register(second);
        var a = store().createChat(project.id(), "Task A");
        var b = store().createChat(project.id(), "Task B");
        var c = store().createChat(other.id(), null);
        var loaded = store().read();
        assertEquals(2, loaded.projects().size());
        assertEquals(java.util.List.of(a, b), loaded.projects().get(0).chats());
        assertEquals(java.util.List.of(c), loaded.projects().get(1).chats());
        assertNotEquals(a.id(), b.id());
        assertNotEquals(a.id(), c.id());
        assertEquals("New Chat", c.name());
        assertEquals(first.toRealPath(), store().resolveRegisteredDirectory(first.toString()));
    }

    @Test void newProjectCreatesOnlyItsFolderAndPersistsCanonicalIdentity() throws Exception {
        Path parent = Files.createDirectory(temp.resolve("projects"));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), parent);
        var project = store().createProject(alias, "New project");
        assertEquals(parent.resolve("New project").toRealPath().toString(), project.workingDirectory());
        assertEquals("New project", project.name());
        assertEquals(project, store().read().projects().get(0));
        assertEquals(project.id(), store().register(parent.resolve("New project")).id());
        try (var children = Files.list(Path.of(project.workingDirectory()))) { assertEquals(0, children.count()); }
        var chat = store().createChat(project.id(), "First chat");
        assertEquals(chat, store().read().projects().get(0).chats().get(0));
    }

    @Test void newProjectRejectsTraversalMissingParentsAndExistingTargets() throws Exception {
        for (String name : new String[] {"", ".", "..", "../escape", "a/b", "a\\b", "a\nb", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> store().createProject(temp, name));
        }
        assertThrows(IllegalArgumentException.class, () -> store().createProject(Path.of("relative"), "new"));
        assertThrows(IOException.class, () -> store().createProject(temp.resolve("missing"), "new"));
        Path existing = Files.createDirectory(temp.resolve("existing"));
        Files.writeString(existing.resolve("keep.txt"), "untouched");
        Path alias = Files.createSymbolicLink(temp.resolve("existing-alias"), existing);
        assertThrows(IOException.class, () -> store().createProject(temp, "existing"));
        assertThrows(IOException.class, () -> store().createProject(temp, alias.getFileName().toString()));
        assertEquals("untouched", Files.readString(existing.resolve("keep.txt")));
        assertTrue(store().read().projects().isEmpty());
        assertFalse(Files.exists(temp.resolve("new")));
    }

    @Test void corruptIndexDoesNotCreateAProjectFolder() throws Exception {
        store().register(temp);
        Files.writeString(temp.resolve(".kompile/chat-workspace.json"), "{bad json");
        assertThrows(IOException.class, () -> store().createProject(temp, "new"));
        assertFalse(Files.exists(temp.resolve("new")));
    }

    @Test void failedIndexWriteOffersRecoveryWithoutDeletingTheNewFolder() throws Exception {
        Path indexDirectory = temp.resolve(".kompile");
        store().register(temp);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(indexDirectory).supportsFileAttributeView("posix"));
        var original = Files.getPosixFilePermissions(indexDirectory);
        try {
            Files.setPosixFilePermissions(indexDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(indexDirectory), "Requires unprivileged permission checks");
            IOException failure = assertThrows(IOException.class, () -> store().createProject(temp, "new"));
            assertTrue(Files.isDirectory(temp.resolve("new")));
            assertTrue(failure.getMessage().contains("Add existing folder"));
            assertTrue(failure.getMessage().contains(temp.resolve("new").toString()));
            assertEquals(1, store().read().projects().size());
        } finally { Files.setPosixFilePermissions(indexDirectory, original); }
        assertEquals(temp.resolve("new").toRealPath().toString(), store().register(temp.resolve("new")).workingDirectory());
        assertEquals(2, store().read().projects().size());
    }

    @Test void onlyExplicitCanonicalRootsAreAllowed() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path other = Files.createDirectory(temp.resolve("other"));
        store().register(root);
        assertThrows(IOException.class, () -> store().resolveRegisteredDirectory(nested.toString()));
        assertThrows(IOException.class, () -> store().resolveRegisteredDirectory(other.toString()));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), root);
        assertEquals(root.toRealPath(), store().resolveRegisteredDirectory(alias.toString()));
        Files.delete(alias);
        Files.createSymbolicLink(alias, other);
        assertThrows(IOException.class, () -> store().resolveRegisteredDirectory(alias.toString()));
    }

    @Test void badInputsNeverChangeTheIndex() throws Exception {
        var project = store().register(temp);
        assertThrows(IllegalArgumentException.class, () -> store().createChat("unknown", "Chat"));
        assertThrows(IllegalArgumentException.class, () -> store().createChat(project.id(), "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> store().createChat(project.id(), "a".repeat(257)));
        assertThrows(IOException.class, () -> store().register(temp.resolve("missing")));
        assertTrue(store().read().projects().get(0).chats().isEmpty());
    }

    @Test void concurrentStoreInstancesDoNotLoseProjects() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                Path root = Files.createDirectory(temp.resolve("project-" + i));
                tasks.add(executor.submit(() -> { store().register(root); return null; }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(12, store().read().projects().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void corruptIndexIsNotOverwritten() throws Exception {
        store().register(temp);
        Path index = temp.resolve(".kompile/chat-workspace.json");
        for (String invalid : java.util.List.of("{bad json", "null", "{\"version\":1,\"projects\":[null]}")) {
            Files.writeString(index, invalid);
            assertThrows(IOException.class, () -> store().register(temp));
            assertEquals(invalid, Files.readString(index));
        }
    }
}
