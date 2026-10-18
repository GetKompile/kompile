package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.adapters.ClaudeCodeAdapter;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ClaudeTranscriptLinkTest {
    @TempDir Path temp;

    private Path conversations() {
        return KompileHome.homeDirectory().toPath().resolve("conversations");
    }

    private String owner() {
        // TemporaryUserHome is class-scoped; each @TempDir/test needs its own session ID.
        return temp.getFileName().toString();
    }

    private ClaudeTranscriptLink publisher(String owner) {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter() {
            @Override protected Path rootDir() { return temp.resolve("projects"); }
        };
        return new ClaudeTranscriptLink(conversations(), owner, adapter);
    }

    private Path source(String id) throws Exception {
        Path file = Files.createDirectories(temp.resolve("projects/custom-project"))
                .resolve(id + ".jsonl");
        Files.writeString(file, "first record\n");
        return file;
    }

    @Test
    void exposesLiveUpdatesWithoutCopyingAndDeduplicatesTheNativeAssociation() throws Exception {
        Path nativeFile = source("native-1");
        Files.createDirectories(conversations());
        Files.writeString(conversations().resolve(owner() + ".txt"), "> first prompt\n");
        ClaudeTranscriptLink publisher = publisher(owner());
        assertTrue(publisher.publish("native-1"));
        Path link = conversations().resolve(owner() + "/claude-code/native-1.jsonl");
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.isSameFile(nativeFile, link));
        Files.writeString(nativeFile, "second record\n", StandardOpenOption.APPEND);
        assertEquals("first record\nsecond record\n", Files.readString(link));
        assertTrue(publisher.publish("native-1"));
        assertTrue(publisher(owner()).publish("native-1"), "restart is idempotent");
        assertEquals(1, Files.readString(conversations().resolve(owner() + ".txt"))
                .lines().filter("[harvested:native-1]"::equals).count());
        source("native-2");
        assertTrue(publisher.publish("native-2"));
        assertTrue(publisher.publish("native-1"));
        assertEquals("native-1", ChatHistory.resolveNativeSessionId(owner(), "claude"),
                "A -> B -> A updates the current association");
        ChatHistory.recordNativeSessionId(owner(), "external-native");
        assertTrue(publisher.publish("native-1"));
        assertEquals("native-1", ChatHistory.resolveNativeSessionId(owner(), "claude"),
                "cached link lookup must not cache an association another writer changed");
        Path pointer = link.resolveSibling("native-1.path");
        assertEquals(nativeFile.toString(), Files.readString(pointer).strip());
        Files.delete(nativeFile);
        assertFalse(Files.exists(link), "link is not an archive; Claude controls retention");
        assertTrue(Files.exists(conversations().resolve(owner() + ".txt")));
    }

    @Test
    void retriesWhenNativeFileIsNotYetWrittenAndKeepsRegeneratedSessions() throws Exception {
        ClaudeTranscriptLink publisher = publisher(owner());
        assertFalse(publisher.publish("native-1"));
        assertFalse(Files.exists(conversations().resolve(owner())));
        source("native-1");
        assertTrue(publisher.publish("native-1"));
        source("native-2");
        assertTrue(publisher.publish("native-2"));
        assertTrue(Files.isSymbolicLink(conversations().resolve(owner() + "/claude-code/native-1.jsonl")));
        assertTrue(Files.isSymbolicLink(conversations().resolve(owner() + "/claude-code/native-2.jsonl")));
        assertFalse(Files.exists(conversations().resolve(owner() + ".txt")), "no empty transcript stubs");
    }

    @Test
    void keepsAReadablePointerWhenSymlinksAreDenied() throws Exception {
        Path nativeFile = source("native-1");
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter() {
            @Override protected Path rootDir() { return temp.resolve("projects"); }
        };
        ClaudeTranscriptLink publisher = new ClaudeTranscriptLink(conversations(), owner(), adapter,
                (link, target) -> { throw new java.nio.file.AccessDeniedException(link.toString()); });
        assertTrue(publisher.publish("native-1"));
        Path directory = conversations().resolve(owner()).resolve("claude-code");
        assertFalse(Files.exists(directory.resolve("native-1.jsonl")));
        assertEquals(nativeFile.toString(), Files.readString(directory.resolve("native-1.path")).strip());
        assertTrue(publisher.publish("native-1"));
        assertEquals("first record\n", Files.readString(nativeFile));
    }

    @Test
    void refusesTraversalAndNeverReplacesAnExistingTranscript() throws Exception {
        source("native-1");
        assertFalse(publisher("../outside").publish("native-1"));
        assertFalse(publisher(owner()).publish("../native-1"));
        Path directory = Files.createDirectories(conversations().resolve(owner() + "/claude-code"));
        Path conflict = directory.resolve("native-1.jsonl");
        Files.writeString(conflict, "do not replace");
        assertFalse(publisher(owner()).publish("native-1"));
        assertEquals("do not replace", Files.readString(conflict));
    }
}
