/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads an image payload (bitmap) from the platform clipboard and stages it as a
 * temporary PNG/JPEG file so the chat REPL can attach it like any other image.
 *
 * <p>Mirrors the Claude Code {@code chat:imagePaste} behavior: the clipboard must
 * contain actual image data — a copied file-path string yields an empty result and
 * the caller falls back to a normal text paste. Native helpers cover macOS
 * (osascript {@code «class PNGf»}), Wayland ({@code wl-paste}), X11 ({@code xclip}),
 * and Windows/WSL (PowerShell {@code Clipboard::GetImage}).</p>
 */
public final class ImageClipboardSupport {

    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final int HELPER_TIMEOUT_SECONDS = 8;

    private ImageClipboardSupport() {}

    /**
     * Read the clipboard image and stage it under the system temp directory.
     *
     * @return the staged temp file path, or empty when the clipboard holds no image
     *         (or no platform helper is available). The text-paste fallback remains
     *         with the caller.
     */
    public static Optional<Path> readClipboardImage() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                return readMacImage();
            }
            if (os.contains("win")) {
                return readWindowsImage("powershell.exe");
            }
            if (isWsl()) {
                return readWindowsImage("powershell.exe");
            }
            String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
            if (waylandDisplay != null && !waylandDisplay.isEmpty()) {
                try {
                    Optional<Path> staged = readWaylandImage();
                    if (staged.isPresent()) return staged;
                } catch (IOException noWlPaste) {
                    // Without wl-clipboard an XWayland session still has xclip below.
                }
            }
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isEmpty()) {
                Optional<Path> staged = readX11Image();
                if (staged.isPresent()) return staged;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // No image payload or helper failed — caller falls back to text paste.
        }
        return Optional.empty();
    }

    private static Optional<Path> readMacImage() throws IOException, InterruptedException {
        // PNGf covers screenshots and most app copies; JPEG is the fallback for apps
        // that place only that flavor. TIFF is not tried: chat attachments carry PNG,
        // JPEG, GIF and WebP only, so staged() would reject it anyway.
        for (String flavor : new String[]{"«class PNGf»", "«class JPEG»"}) {
            Path target = newTempFile(".png");
            String script = "set pngData to (the clipboard as " + flavor + ")\n"
                    + "set imgFile to open for access POSIX file \"" + target + "\" with write permission\n"
                    + "set eof imgFile to 0\n"
                    + "write pngData to imgFile\n"
                    + "close access imgFile\n";
            if (runHelper(new String[]{"osascript", "-e", script}) && staged(target)) {
                return Optional.of(target);
            }
            Files.deleteIfExists(target);
        }
        return Optional.empty();
    }

    private static Optional<Path> readWaylandImage() throws IOException, InterruptedException {
        for (String type : new String[]{"image/png", "image/jpeg", "image"}) {
            String ext = type.endsWith("jpeg") ? ".jpg" : ".png";
            Path target = newTempFile(ext);
            if (runHelperRedirected(new String[]{"wl-paste", "--type", type}, target) && staged(target)) {
                return Optional.of(target);
            }
            Files.deleteIfExists(target);
        }
        return Optional.empty();
    }

    private static Optional<Path> readX11Image() throws IOException, InterruptedException {
        for (String type : new String[]{"image/png", "image/jpeg"}) {
            String ext = type.endsWith("jpeg") ? ".jpg" : ".png";
            Path target = newTempFile(ext);
            if (runHelperRedirected(
                    new String[]{"xclip", "-selection", "clipboard", "-t", type, "-o"}, target)
                    && staged(target)) {
                return Optional.of(target);
            }
            Files.deleteIfExists(target);
        }
        return Optional.empty();
    }

    private static Optional<Path> readWindowsImage(String executable)
            throws IOException, InterruptedException {
        Path target = newTempFile(".png");
        String script = "Add-Type -AssemblyName System.Windows.Forms; "
                + "Add-Type -AssemblyName System.Drawing; "
                + "$img = [System.Windows.Forms.Clipboard]::GetImage(); "
                + "if ($img) { $img.Save('" + target + "', [System.Drawing.Imaging.ImageFormat]::Png) }";
        // Clipboard access requires STA; a missing image still exits 0 with no file.
        runHelper(new String[]{executable, "-NoProfile", "-STA", "-Command", script});
        if (staged(target)) {
            return Optional.of(target);
        }
        Files.deleteIfExists(target);
        return Optional.empty();
    }

    private static Path newTempFile(String suffix) throws IOException {
        Path target = Files.createTempFile("kompile-clipboard-", suffix);
        Files.deleteIfExists(target); // helpers must create/write it themselves
        target.toFile().deleteOnExit();
        return target;
    }

    private static boolean staged(Path target) throws IOException {
        return Files.isRegularFile(target) && Files.size(target) > 0
                && Files.size(target) <= MAX_IMAGE_BYTES
                && imageMimeType(target).isPresent();
    }

    /**
     * The attachable image type (PNG, JPEG, GIF or WebP) named by a file's leading
     * bytes. A helper's exit code proves nothing here: xclip serves the selection's
     * text for any requested target, so after text was copied in the chat, Ctrl+V
     * staged that text as {@code image/png} and attached it as {@code [Image #1]}
     * instead of pasting it.
     */
    static Optional<String> imageMimeType(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return imageMimeType(in.readNBytes(12));
        }
    }

    static Optional<String> imageMimeType(byte[] head) {
        if (startsWith(head, 0, 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n')) {
            return Optional.of("image/png");
        }
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, 0, 'G', 'I', 'F', '8')) {
            return Optional.of("image/gif");
        }
        if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    private static boolean runHelper(String[] command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static boolean runHelperRedirected(String[] command, Path target)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(target.toFile()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static boolean isWsl() {
        try {
            String release = Files.readString(Path.of("/proc/version"));
            return release.toLowerCase().contains("microsoft");
        } catch (Exception e) {
            return false;
        }
    }
}
