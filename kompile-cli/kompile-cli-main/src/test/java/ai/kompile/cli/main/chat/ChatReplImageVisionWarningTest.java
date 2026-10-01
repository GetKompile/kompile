package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pasted clipboard image and an image path typed into the prompt warn when the active
 * model is known to be text-only — once per paste and once per message — and never for a
 * vision model or a model nothing knows about. The image is attached either way.
 */
@TemporaryUserHome
class ChatReplImageVisionWarningTest {
    /** Not a loopback address, so the capability comes from the catalogs, never a probe. */
    private static final String UNUSED_BASE_URL = "http://unused.invalid";

    @TempDir Path work;

    @Test
    void textOnlyModelWarnsOncePerPasteAndOncePerMessage() throws IOException {
        Path first = png("first.png");
        Path second = png("second.png");
        Path third = png("third.png");
        String missing = work.resolve("missing.png").toString();
        List<String> notices = noticesWhile(
                new ChatConfig("deepseek", null, "deepseek-chat", UNUSED_BASE_URL), repl -> {
            assertTrue(repl.attachImageFromClipboard(first));
            assertTrue(repl.attachImageFromClipboard(first), "a repeated paste keeps the queued image");
            assertEquals("compare [Image #2] and [Image #3]",
                    repl.autoAttachImagePaths("compare " + second + " and " + third));
            assertEquals("see " + missing, repl.autoAttachImagePaths("see " + missing));
            assertEquals("no images here", repl.autoAttachImagePaths("no images here"));
        });

        List<String> warnings = matching(notices, "is text-only");
        assertEquals(2, warnings.size(), "one warning for the paste, one for the message: " + notices);
        assertTrue(warnings.get(0).contains("Model 'deepseek-chat'"), warnings.get(0));
        assertEquals(2, matching(notices, "Attached").size(), notices.toString());
    }

    @Test
    void visionModelAttachesWithoutWarning() throws IOException {
        Path first = png("first.png");
        Path second = png("second.png");
        List<String> notices = noticesWhile(
                new ChatConfig("openai", null, "gpt-4o", UNUSED_BASE_URL), repl -> {
            assertTrue(repl.attachImageFromClipboard(first));
            assertEquals("look at [Image #2]", repl.autoAttachImagePaths("look at " + second));
        });

        assertEquals(List.of(), matching(notices, "is text-only"));
        assertEquals(1, matching(notices, "Attached").size(), "the notice lane is live: " + notices);
    }

    @Test
    void modelNothingKnowsAboutAttachesWithoutWarning() throws IOException {
        Path first = png("first.png");
        Path second = png("second.png");
        List<String> notices = noticesWhile(
                new ChatConfig("openai", null, "zz-unlisted-model", UNUSED_BASE_URL), repl -> {
            assertTrue(repl.attachImageFromClipboard(first));
            assertEquals("look at [Image #2]", repl.autoAttachImagePaths("look at " + second));
        });

        assertEquals(List.of(), matching(notices, "is text-only"));
        assertEquals(1, matching(notices, "Attached").size(), "the notice lane is live: " + notices);
    }

    @Test
    void noChatLoopMeansNoWarning() {
        assertEquals(Optional.empty(), ChatCommandRouter.textOnlyModelWarning(null));
    }

    /** Runs the actions against a real local-mode REPL and returns the notices it showed. */
    private List<String> noticesWhile(ChatConfig config, Consumer<ChatRepl> actions) {
        List<String> notices = new CopyOnWriteArrayList<>();
        ChatUiSession ui = new ChatUiSession();
        try (var ignored = ui.bind()) {
            ChatRepl repl = new ChatRepl(null, null, "vision-warning-" + System.nanoTime(),
                    false, "default", false, config, work);
            try {
                ChatCompleter.setAlertOutput(notices::add);
                actions.accept(repl);
            } finally {
                repl.close();
            }
        } finally {
            ui.close();
        }
        return notices;
    }

    private static List<String> matching(List<String> notices, String text) {
        return notices.stream().filter(notice -> notice.contains(text)).toList();
    }

    private Path png(String name) throws IOException {
        Path file = work.resolve(name);
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }
}
