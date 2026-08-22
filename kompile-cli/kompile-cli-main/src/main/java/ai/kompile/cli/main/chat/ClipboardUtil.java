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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import org.jline.terminal.Terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Clipboard utilities for the TUI.
 * <p>
 * Uses OSC 52 escape sequence first (works over SSH and in most modern terminals),
 * then falls back to native clipboard commands (pbcopy, xclip, xsel, wl-copy).
 */
public class ClipboardUtil {

    private static final int MAX_CLIPBOARD_BYTES = 32 * 1024 * 1024;

    private ClipboardUtil() {}

    /**
     * Copy text to the system clipboard.
     * Tries OSC 52 first, then falls back to native commands.
     *
     * @return true if the copy succeeded via at least one method
     */
    public static boolean copyToClipboard(String text) {
        return copyToClipboard(text, null);
    }

    /**
     * Copy through the active terminal when one is available, keeping OSC 52 on
     * the terminal output stream instead of a potentially unrelated System.out.
     */
    public static boolean copyToClipboard(String text, Terminal terminal) {
        if (text == null || text.isEmpty()) return false;

        boolean osc52Ok = tryOsc52(text, terminal);
        boolean nativeOk = tryNativeClipboard(text);
        return osc52Ok || nativeOk;
    }

    /**
     * Read text from the platform clipboard for managed mouse paste. OSC 52
     * clipboard queries are deliberately not used here: terminal responses are
     * asynchronous input and would race JLine's key decoder. Native readers are
     * deterministic and cover macOS, Wayland, X11, Windows, and WSL.
     */
    public static Optional<String> readFromClipboard() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return execCapture(new String[]{"pbpaste"});
        }

        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (waylandDisplay != null && !waylandDisplay.isEmpty()) {
            Optional<String> value = execCapture(new String[]{"wl-paste", "--type", "text"});
            if (value.isPresent()) return value;
        }

        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            Optional<String> value = execCapture(
                    new String[]{"xclip", "-selection", "clipboard", "-out"});
            if (value.isPresent()) return value;
            value = execCapture(new String[]{"xsel", "--clipboard", "--output"});
            if (value.isPresent()) return value;
        }

        if (os.contains("win")) {
            return execCapture(new String[]{
                    "powershell.exe", "-NoProfile", "-Command", "Get-Clipboard -Raw"});
        }
        if (isWsl()) {
            return execCapture(new String[]{
                    "powershell.exe", "-NoProfile", "-Command", "Get-Clipboard -Raw"});
        }
        return Optional.empty();
    }

    /**
     * Write OSC 52 escape sequence to stdout.
     * This tells the terminal emulator to set the clipboard contents.
     * Works in most modern terminals (iTerm2, kitty, alacritty, WezTerm, etc.)
     * and crucially works over SSH sessions.
     */
    private static boolean tryOsc52(String text, Terminal terminal) {
        try {
            String b64 = Base64.getEncoder().encodeToString(
                    text.getBytes(StandardCharsets.UTF_8));
            // OSC 52: \033]52;c;<base64-data>\a
            byte[] sequence = ("\033]52;c;" + b64 + "\007")
                    .getBytes(StandardCharsets.UTF_8);
            if (terminal != null) {
                terminal.output().write(sequence);
                terminal.output().flush();
            } else {
                System.out.write(sequence);
                System.out.flush();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Try native clipboard commands in order of preference.
     */
    private static boolean tryNativeClipboard(String text) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return execPipe(new String[]{"pbcopy"}, text);
        }

        // Linux — try wayland first, then X11
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (waylandDisplay != null && !waylandDisplay.isEmpty()) {
            if (execPipe(new String[]{"wl-copy"}, text)) return true;
        }

        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            if (execPipe(new String[]{"xclip", "-selection", "clipboard"}, text)) return true;
            if (execPipe(new String[]{"xsel", "--clipboard", "--input"}, text)) return true;
        }

        // Windows — clip.exe (also works in WSL)
        if (os.contains("win") || isWsl()) {
            return execPipe(new String[]{"clip.exe"}, text);
        }

        return false;
    }

    private static boolean execPipe(String[] cmd, String text) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Optional<String> execCapture(String[] cmd) {
        Process process = null;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process active = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    active.getInputStream().transferTo(bytes);
                    return bytes.toByteArray();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > MAX_CLIPBOARD_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean isWsl() {
        try {
            String release = new String(
                    Files.readAllBytes(Path.of("/proc/version")),
                    StandardCharsets.UTF_8);
            return release.toLowerCase().contains("microsoft");
        } catch (Exception e) {
            return false;
        }
    }

}
