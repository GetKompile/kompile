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
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Clipboard utilities for the TUI.
 * <p>
 * Uses OSC 52 escape sequence first (works over SSH and in most modern terminals),
 * then falls back to native clipboard commands (pbcopy, xclip, xsel, wl-copy).
 */
public class ClipboardUtil {

    /**
     * What a copy achieved. Only a native helper that exited 0 confirms a copy:
     * a terminal consumes OSC 52 silently whether or not it honors it, and
     * VTE-based terminals (GNOME Terminal and most Linux defaults) ignore it.
     */
    public enum CopyResult {
        /** A native clipboard helper took the text. */
        COPIED,
        /** Only the OSC 52 sequence went out; the terminal may have ignored it. */
        OSC52_ONLY,
        /** Neither OSC 52 nor a native helper worked. */
        FAILED
    }

    private static final int MAX_CLIPBOARD_BYTES = 32 * 1024 * 1024;
    /** Helpers that leave a background process serving the selection; see {@link #detached}. */
    private static final Set<String> DAEMONIZING_HELPERS = Set.of("wl-copy", "xclip", "xsel");
    private static final String SETSID = detectSetsid();
    private static final ExecutorService CLIPBOARD_COPY_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "kompile-clipboard-copy");
                thread.setDaemon(true);
                return thread;
            });

    private ClipboardUtil() {}

    /**
     * Copy text to the system clipboard.
     * Tries OSC 52 first, then falls back to native commands.
     */
    public static CopyResult copyToClipboard(String text) {
        return copyToClipboard(text, null);
    }

    /**
     * Copy through the active terminal when one is available, keeping OSC 52 on
     * the terminal output stream instead of a potentially unrelated System.out.
     */
    public static CopyResult copyToClipboard(String text, Terminal terminal) {
        if (text == null || text.isEmpty()) return CopyResult.FAILED;

        boolean osc52Ok = tryOsc52(text, terminal);
        return result(tryNativeClipboard(text), osc52Ok);
    }

    /**
     * Ordered non-blocking copy for mouse widgets. Serial execution guarantees
     * that a slower native helper for an older selection cannot overwrite a
     * newer selection after it completes.
     *
     * @return completes with the outcome once the native helper has finished
     */
    public static CompletableFuture<CopyResult> copyToClipboardAsync(String text, Terminal terminal) {
        if (text == null || text.isEmpty()) return CompletableFuture.completedFuture(CopyResult.FAILED);
        // OSC 52 is a single ordered terminal write and should take effect
        // immediately. Potentially slow native helpers stay off JLine's thread.
        boolean osc52Ok = tryOsc52(text, terminal);
        return CompletableFuture.supplyAsync(
                () -> result(tryNativeClipboard(text), osc52Ok), CLIPBOARD_COPY_EXECUTOR);
    }

    /** The notice for a copy of {@code characters} characters; it claims success only when confirmed. */
    public static String describe(CopyResult result, int characters) {
        return describe(result, characters, nativeCopyCommands().stream().map(c -> c[0]).toList());
    }

    static String describe(CopyResult result, int characters, List<String> helpers) {
        String helperProblem = helpers.isEmpty()
                ? "no clipboard helper for this session"
                : String.join("/", helpers) + " failed or not installed";
        return switch (result) {
            case COPIED -> "✓ Copied " + characters + " characters to the clipboard";
            case OSC52_ONLY -> "Copy unconfirmed: sent " + characters
                    + " characters via OSC 52 only (" + helperProblem + ")";
            case FAILED -> "Copy failed: " + helperProblem;
        };
    }

    private static CopyResult result(boolean nativeOk, boolean osc52Ok) {
        if (nativeOk) return CopyResult.COPIED;
        return osc52Ok ? CopyResult.OSC52_ONLY : CopyResult.FAILED;
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
        for (String[] command : nativeCopyCommands()) {
            if (execPipe(command, text)) return true;
        }
        return false;
    }

    /** The native copy commands this session can try, in order of preference. */
    private static List<String[]> nativeCopyCommands() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String[]> commands = new ArrayList<>();
        if (os.contains("mac")) {
            commands.add(new String[]{"pbcopy"});
            return commands;
        }

        // Linux — try wayland first, then X11
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (waylandDisplay != null && !waylandDisplay.isEmpty()) {
            commands.add(new String[]{"wl-copy"});
        }

        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            commands.add(new String[]{"xclip", "-selection", "clipboard"});
            commands.add(new String[]{"xsel", "--clipboard", "--input"});
        }

        // Windows — clip.exe (also works in WSL)
        if (os.contains("win") || isWsl()) {
            commands.add(new String[]{"clip.exe"});
        }
        return commands;
    }

    /**
     * xclip forks a background process that keeps serving the selection after the
     * command exits (wl-copy and xsel do the same). Started from the chat it stayed
     * in the chat's process group and session, so the next Ctrl+C (SIGINT to the
     * whole foreground group) or closing the terminal (SIGHUP) killed it, and the
     * copied text was gone unless a desktop clipboard manager had taken it over.
     * setsid gives the helper a session of its own. A ProcessBuilder child never
     * leads a process group, so setsid execs in place and the exit status is still
     * the helper's.
     */
    static String[] detached(String[] cmd) {
        if (SETSID == null || !DAEMONIZING_HELPERS.contains(cmd[0])) return cmd;
        String[] wrapped = new String[cmd.length + 1];
        wrapped[0] = SETSID;
        System.arraycopy(cmd, 0, wrapped, 1, cmd.length);
        return wrapped;
    }

    private static String detectSetsid() {
        for (String candidate : new String[]{"/usr/bin/setsid", "/bin/setsid"}) {
            if (new File(candidate).canExecute()) return candidate;
        }
        return null;
    }

    private static boolean execPipe(String[] cmd, String text) {
        Process process = null;
        try {
            process = new ProcessBuilder(detached(cmd))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process active = process;
            CompletableFuture<Boolean> write = CompletableFuture.supplyAsync(() -> {
                try (OutputStream os = active.getOutputStream()) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            if (!write.get(5, TimeUnit.SECONDS)) return false;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | ExecutionException | TimeoutException e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
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
