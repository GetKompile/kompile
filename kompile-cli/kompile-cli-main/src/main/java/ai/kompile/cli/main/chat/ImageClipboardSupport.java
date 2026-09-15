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
                Optional<Path> staged = readWaylandImage();
                if (staged.isPresent()) return staged;
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
        // PNGf covers screenshots and most app copies; JPEG and TIFF are fallbacks
        // for apps that place those flavors instead.
        for (String flavor : new String[]{"«class PNGf»", "«class JPEG»", "«class TIFF»"}) {
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
                && Files.size(target) <= MAX_IMAGE_BYTES;
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
