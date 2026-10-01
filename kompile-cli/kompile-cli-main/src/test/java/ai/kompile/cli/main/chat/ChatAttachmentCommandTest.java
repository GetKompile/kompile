package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.exec.ChatAttachmentLoader;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The REPL's {@code /image} and {@code /file}, and the turn that sends what they queued,
 * follow the rules a headless {@code --attachment} turn does.
 */
class ChatAttachmentCommandTest {

    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path tempDir;

    private final List<ChatRepl.PendingAttachment> pending = new ArrayList<>();

    @AfterEach
    void releaseCompleterQueue() {
        // ChatMessageHandler's constructor installs the completer's queue supplier.
        ChatCompleter.setQueueSupplier(null);
    }

    @Test
    void commandsQueueImagesAsImagesAndEverythingElseAsFiles() throws IOException {
        Path png = Files.write(tempDir.resolve("plot.png"), new byte[]{1, 2, 3});
        Path notes = Files.writeString(tempDir.resolve("notes.txt"), "hello");
        Path svg = Files.writeString(tempDir.resolve("diagram.svg"), "<svg/>");

        ChatCommandRouter router = router();
        router.handleSlashCommand("/image " + png);
        router.handleSlashCommand("/file " + png);
        router.handleSlashCommand("/file " + notes);
        router.handleSlashCommand("/file " + svg);
        router.handleSlashCommand("/image " + notes);

        assertEquals(4, pending.size(), pending::toString);
        assertEquals(new ChatRepl.PendingAttachment(png, "image/png", true), pending.get(0));
        assertEquals(pending.get(0), pending.get(1));
        assertEquals(notes, pending.get(2).path());
        assertFalse(pending.get(2).isImage());
        // SVG is XML: it goes as a file the model reads, not as an image.
        assertEquals(svg, pending.get(3).path());
        assertFalse(pending.get(3).isImage());
    }

    @Test
    void commandsRefuseWhatAHeadlessTurnRefuses() throws IOException {
        Path bmp = Files.write(tempDir.resolve("scan.bmp"), new byte[]{'B', 'M', 0, 0});
        Path big = sparse("big.png", ChatAttachmentLoader.MAX_ATTACHMENT_BYTES + 1);

        ChatCommandRouter router = router();
        router.handleSlashCommand("/image " + bmp);
        router.handleSlashCommand("/file " + bmp);
        router.handleSlashCommand("/image " + big);
        router.handleSlashCommand("/file " + big);

        assertTrue(pending.isEmpty(), pending::toString);
        assertThrows(IOException.class, () -> ChatAttachmentLoader.load(List.of(bmp)));
        assertThrows(IOException.class, () -> ChatAttachmentLoader.load(List.of(big)));
    }

    @Test
    void replTurnPassesBinaryFilesByPathLikeAHeadlessTurn() throws IOException {
        Path pdf = Files.write(tempDir.resolve("report.pdf"),
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', '%',
                        (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3});
        router().handleSlashCommand("/file " + pdf);

        List<DirectLlmClient.AttachmentInput> sent = handler().loadAttachments();

        assertEquals(1, sent.size());
        assertFalse(sent.get(0).isImage());
        assertTrue(sent.get(0).textContent().startsWith("[Binary file (14 bytes)"),
                sent.get(0).textContent());
        assertEquals(ChatAttachmentLoader.load(List.of(pdf)).get(0).textContent(),
                sent.get(0).textContent());
        assertTrue(pending.isEmpty());
    }

    @Test
    void replTurnSkipsWhatPassesTheTotalLimitAndSendsTheRest() throws IOException {
        long fiveMib = ChatAttachmentLoader.MAX_ATTACHMENT_BYTES;
        for (Path image : List.of(sparse("a.png", fiveMib), sparse("b.png", fiveMib),
                sparse("c.png", fiveMib), sparse("d.png", 4 * MIB), sparse("e.png", fiveMib))) {
            pending.add(new ChatRepl.PendingAttachment(image, "image/png", true));
        }
        Path notes = Files.writeString(tempDir.resolve("notes.txt"), "hello");
        pending.add(new ChatRepl.PendingAttachment(notes, "text/plain", false));

        List<DirectLlmClient.AttachmentInput> sent = handler().loadAttachments();

        // 19 MiB fit; e.png would make 24 of the 20 allowed, so it stays behind; the note fits.
        assertEquals(List.of("a.png", "b.png", "c.png", "d.png", "notes.txt"),
                sent.stream().map(a -> Path.of(a.path()).getFileName().toString()).toList());
        assertEquals("hello", sent.get(4).textContent());
    }

    /** A file of the given length that takes no disk space. */
    private Path sparse(String name, long size) throws IOException {
        Path path = tempDir.resolve(name);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(size);
        }
        return path;
    }

    private ChatCommandRouter router() {
        TerminalRenderer renderer = new TerminalRenderer();
        return new ChatCommandRouter(
                null, null, null, null,
                null, null, "attachment-session", true,
                null, null, renderer, new AsciiRenderer(renderer),
                null, null, null, null, null, null, null, null, null,
                pending, null);
    }

    private ChatMessageHandler handler() {
        return new ChatMessageHandler(mock(ChatRepl.class), null, null, new ObjectMapper(),
                "attachment-session", true, null, null, null, new TerminalRenderer(), null,
                mock(AgenticChatLoop.class), mock(BackgroundTaskManager.class),
                mock(MessageQueue.class), new AtomicBoolean(), pending, null, null);
    }
}
