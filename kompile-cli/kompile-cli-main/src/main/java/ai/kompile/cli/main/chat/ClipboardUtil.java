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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Clipboard utilities for the TUI.
 * <p>
 * Uses OSC 52 escape sequence first (works over SSH and in most modern terminals),
 * then falls back to native clipboard commands (pbcopy, xclip, xsel, wl-copy).
 */
public class ClipboardUtil {

    private ClipboardUtil() {}

    /**
     * Copy text to the system clipboard.
     * Tries OSC 52 first, then falls back to native commands.
     *
     * @return true if the copy succeeded via at least one method
     */
    public static boolean copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return false;

        boolean osc52Ok = tryOsc52(text);
        boolean nativeOk = tryNativeClipboard(text);
        return osc52Ok || nativeOk;
    }

    /**
     * Write OSC 52 escape sequence to stdout.
     * This tells the terminal emulator to set the clipboard contents.
     * Works in most modern terminals (iTerm2, kitty, alacritty, WezTerm, etc.)
     * and crucially works over SSH sessions.
     */
    private static boolean tryOsc52(String text) {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(
                    text.getBytes(StandardCharsets.UTF_8));
            // OSC 52: \033]52;c;<base64-data>\a
            String osc52 = "\033]52;c;" + b64 + "\007";
            System.out.print(osc52);
            System.out.flush();
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

    private static boolean isWsl() {
        try {
            String release = new String(
                    java.nio.file.Files.readAllBytes(
                            java.nio.file.Path.of("/proc/version")),
                    StandardCharsets.UTF_8);
            return release.toLowerCase().contains("microsoft");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Strip ANSI escape codes from text so clipboard content is clean.
     */
    public static String stripAnsi(String text) {
        if (text == null) return null;
        return text.replaceAll("\033\\[[;\\d]*m", "")
                   .replaceAll("\033\\][^\007]*\007", "");
    }
}
