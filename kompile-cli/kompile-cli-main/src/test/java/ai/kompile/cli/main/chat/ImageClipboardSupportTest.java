package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only real image bytes are staged as a clipboard image. xclip answers a request for
 * image/png with the selection's text, so text copied in the chat must come back from
 * Ctrl+V as text, not as an attached {@code [Image #1]}.
 */
class ImageClipboardSupportTest {

    @TempDir Path work;

    @Test
    void textServedForAnImageTargetIsNotAnImage() throws IOException {
        Path served = work.resolve("kompile-clipboard-served.png");
        Files.writeString(served, "  kompile > copied transcript line\n");

        assertEquals(Optional.empty(), ImageClipboardSupport.imageMimeType(served));
        assertEquals(Optional.empty(), ImageClipboardSupport.imageMimeType(
                "BM looks like a bitmap header".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), ImageClipboardSupport.imageMimeType(new byte[0]));
    }

    @Test
    void attachableImagesAreNamedByTheirBytes() throws IOException {
        assertEquals(Optional.of("image/png"), ImageClipboardSupport.imageMimeType(encoded("png")));
        assertEquals(Optional.of("image/jpeg"), ImageClipboardSupport.imageMimeType(encoded("jpg")));
        assertEquals(Optional.of("image/gif"), ImageClipboardSupport.imageMimeType(encoded("gif")));
        byte[] webp = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        assertEquals(Optional.of("image/webp"), ImageClipboardSupport.imageMimeType(webp));
    }

    @Test
    void imagesChatAttachmentsCannotCarryAreNotStaged() {
        byte[] tiff = {'I', 'I', '*', 0, 8, 0, 0, 0};
        assertEquals(Optional.empty(), ImageClipboardSupport.imageMimeType(tiff));
    }

    /** A 2x2 image written by ImageIO, so the header is a real encoder's. */
    private Path encoded(String format) throws IOException {
        Path file = work.resolve("image." + format);
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, format, file.toFile()), "no ImageIO writer for " + format);
        return file;
    }
}
