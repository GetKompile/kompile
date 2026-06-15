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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spawns new terminal windows running a given command.
 * <p>
 * Supports auto-detection of available terminals on Linux and macOS.
 * Users can override the terminal via {@link TerminalConfig} or command-line flag.
 * <p>
 * If running inside tmux, defaults to creating new tmux windows instead of
 * spawning external terminal emulators.
 * <p>
 * Key design decisions:
 * <ul>
 *   <li>gnome-terminal uses client-server architecture (D-Bus) — it forks and the client exits.
 *       We use {@code --wait} so the caller can detect failures, and wrap the command in
 *       {@code bash -c} with a trailing {@code exec $SHELL} fallback so the terminal stays
 *       open on error.</li>
 *   <li>All terminals receive an absolute path to the binary — never rely on PATH in the
 *       spawned shell environment since it may differ from the caller's.</li>
 * </ul>
 */
public class TerminalLauncher {

    private final TerminalConfig config;

    public TerminalLauncher(TerminalConfig config) {
        this.config = config;
    }

    /**
     * Launch a command in a new terminal window.
     *
     * @param command    the full command to execute as a list of args
     * @param workingDir the directory to start the terminal in
     * @param title      window title (best-effort, not all terminals support it)
     * @return the spawned Process, or null if no terminal could be found
     */
    public Process launch(List<String> command, Path workingDir, String title) throws IOException {
        // Check if we're in tmux — prefer tmux windows
        if (isInsideTmux()) {
            return launchTmux(command, workingDir, title);
        }

        // User-configured terminal takes priority
        if (config.isConfigured()) {
            return launchConfigured(command, workingDir, title);
        }

        // Auto-detect based on OS
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return launchMacOS(command, workingDir, title);
        } else if (os.contains("linux")) {
            return launchLinux(command, workingDir, title);
        }

        throw new IOException("No supported terminal emulator found for OS: " + os
                + ". Configure one with: kompile resume-all --set-terminal <command>");
    }

    /**
     * Detect the default terminal emulator for the current system.
     * Returns a human-readable description.
     */
    public String detectTerminal() {
        if (config.isConfigured()) {
            return config.getTerminalCommand() + " (configured)";
        }
        if (isInsideTmux()) {
            return "tmux (detected: running inside tmux session)";
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return detectMacTerminal();
        } else if (os.contains("linux")) {
            return detectLinuxTerminal();
        }
        return "unknown";
    }

    // ── tmux ────────────────────────────────────────────────────────────────

    private boolean isInsideTmux() {
        String tmux = System.getenv("TMUX");
        return tmux != null && !tmux.isEmpty();
    }

    private Process launchTmux(List<String> command, Path workingDir, String title) throws IOException {
        List<String> tmuxCmd = new ArrayList<>();
        tmuxCmd.add("tmux");
        tmuxCmd.add("new-window");
        if (title != null && !title.isEmpty()) {
            tmuxCmd.add("-n");
            tmuxCmd.add(title);
        }
        tmuxCmd.add("-c");
        tmuxCmd.add(workingDir.toAbsolutePath().toString());
        // tmux new-window takes a single shell string as the last arg
        tmuxCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(tmuxCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    // ── User-configured terminal ────────────────────────────────────────────

    private Process launchConfigured(List<String> command, Path workingDir, String title) throws IOException {
        String termBin = config.getTerminalCommand();
        String termArgs = config.getTerminalArgs();

        // If the user configured a known terminal but didn't provide args,
        // add the correct separator/flags automatically
        if (termArgs == null || termArgs.isBlank()) {
            String binName = Path.of(termBin).getFileName().toString();
            termArgs = switch (binName) {
                case "gnome-terminal" -> "--wait --";
                case "konsole" -> "-e";
                case "xfce4-terminal" -> "-e";
                case "alacritty" -> "-e";
                case "xterm" -> "-e";
                case "wezterm" -> "start --";
                default -> "--"; // reasonable default separator
            };
        }

        List<String> termCmd = new ArrayList<>();
        termCmd.add(termBin);
        for (String arg : termArgs.split("\\s+")) {
            if (!arg.isEmpty()) termCmd.add(arg);
        }
        // Wrap in bash -c so the terminal stays open on failure
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    // ── Linux auto-detect ───────────────────────────────────────────────────

    private Process launchLinux(List<String> command, Path workingDir, String title) throws IOException {
        // Try gnome-terminal first (most common on GNOME desktops)
        if (isOnPath("gnome-terminal")) {
            return launchGnomeTerminal(command, workingDir, title);
        }

        // konsole (KDE)
        if (isOnPath("konsole")) {
            return launchKonsole(command, workingDir, title);
        }

        // xfce4-terminal
        if (isOnPath("xfce4-terminal")) {
            return launchXfce4Terminal(command, workingDir, title);
        }

        // kitty
        if (isOnPath("kitty")) {
            return launchKitty(command, workingDir, title);
        }

        // alacritty
        if (isOnPath("alacritty")) {
            return launchAlacritty(command, workingDir, title);
        }

        // foot (Wayland)
        if (isOnPath("foot")) {
            return launchFoot(command, workingDir, title);
        }

        // wezterm
        if (isOnPath("wezterm")) {
            return launchWezterm(command, workingDir, title);
        }

        // xterm (fallback)
        if (isOnPath("xterm")) {
            return launchXterm(command, workingDir, title);
        }

        throw new IOException("No terminal emulator found on PATH. Install one of: "
                + "gnome-terminal, konsole, kitty, alacritty, xterm, or configure with --set-terminal");
    }

    /**
     * gnome-terminal uses D-Bus client-server architecture.
     * Without --wait, the client process exits immediately after sending the request.
     * We use --wait so the Process object stays alive, and wrap the command in bash -c
     * with a fallback shell so the terminal window stays open on error.
     */
    private Process launchGnomeTerminal(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("gnome-terminal");
        termCmd.add("--wait");
        if (title != null && !title.isEmpty()) {
            termCmd.add("--title");
            termCmd.add(title);
        }
        termCmd.add("--working-directory=" + workingDir.toAbsolutePath());
        termCmd.add("--");
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        // Don't inheritIO — gnome-terminal --wait blocks and we don't want it holding our stdout
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    private Process launchKonsole(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("konsole");
        if (title != null && !title.isEmpty()) {
            termCmd.add("-p");
            termCmd.add("tabtitle=" + title);
        }
        termCmd.add("--workdir");
        termCmd.add(workingDir.toAbsolutePath().toString());
        termCmd.add("-e");
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchXfce4Terminal(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("xfce4-terminal");
        if (title != null && !title.isEmpty()) {
            termCmd.add("--title=" + title);
        }
        termCmd.add("--working-directory=" + workingDir.toAbsolutePath());
        // xfce4-terminal -e takes a single quoted string
        termCmd.add("-e");
        termCmd.add("bash -c " + shellEscape(buildBashWrapperCommand(command)));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchKitty(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("kitty");
        if (title != null && !title.isEmpty()) {
            termCmd.add("--title");
            termCmd.add(title);
        }
        termCmd.add("--directory");
        termCmd.add(workingDir.toAbsolutePath().toString());
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchAlacritty(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("alacritty");
        if (title != null && !title.isEmpty()) {
            termCmd.add("--title");
            termCmd.add(title);
        }
        termCmd.add("--working-directory");
        termCmd.add(workingDir.toAbsolutePath().toString());
        termCmd.add("-e");
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchFoot(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("foot");
        if (title != null && !title.isEmpty()) {
            termCmd.add("--title");
            termCmd.add(title);
        }
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add("cd " + shellEscape(workingDir.toAbsolutePath().toString())
                + " && " + buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchWezterm(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("wezterm");
        termCmd.add("start");
        termCmd.add("--cwd");
        termCmd.add(workingDir.toAbsolutePath().toString());
        termCmd.add("--");
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add(buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private Process launchXterm(List<String> command, Path workingDir, String title) throws IOException {
        List<String> termCmd = new ArrayList<>();
        termCmd.add("xterm");
        if (title != null && !title.isEmpty()) {
            termCmd.add("-title");
            termCmd.add(title);
        }
        termCmd.add("-e");
        termCmd.add("bash");
        termCmd.add("-c");
        termCmd.add("cd " + shellEscape(workingDir.toAbsolutePath().toString())
                + " && " + buildBashWrapperCommand(command));

        ProcessBuilder pb = new ProcessBuilder(termCmd);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private String detectLinuxTerminal() {
        if (isOnPath("gnome-terminal")) return "gnome-terminal";
        if (isOnPath("konsole")) return "konsole";
        if (isOnPath("xfce4-terminal")) return "xfce4-terminal";
        if (isOnPath("kitty")) return "kitty";
        if (isOnPath("alacritty")) return "alacritty";
        if (isOnPath("foot")) return "foot";
        if (isOnPath("wezterm")) return "wezterm";
        if (isOnPath("xterm")) return "xterm";
        return "none found";
    }

    // ── macOS ───────────────────────────────────────────────────────────────

    private Process launchMacOS(List<String> command, Path workingDir, String title) throws IOException {
        if (isOnPath("kitty")) {
            return launchKitty(command, workingDir, title);
        }

        if (isOnPath("alacritty")) {
            return launchAlacritty(command, workingDir, title);
        }

        // Use osascript to open Terminal.app or iTerm2
        boolean hasIterm = isAppInstalled("iTerm");
        String app = hasIterm ? "iTerm" : "Terminal";
        String shellCmd = "cd " + shellEscape(workingDir.toAbsolutePath().toString())
                + " && " + joinShellCommand(command);

        String applescript;
        if ("iTerm".equals(app)) {
            applescript = String.format(
                    "tell application \"iTerm\"\n"
                            + "  create window with default profile command \"%s\"\n"
                            + "end tell",
                    escapeAppleScript(shellCmd));
        } else {
            applescript = String.format(
                    "tell application \"Terminal\"\n"
                            + "  do script \"%s\"\n"
                            + "  activate\n"
                            + "end tell",
                    escapeAppleScript(shellCmd));
        }

        ProcessBuilder pb = new ProcessBuilder("osascript", "-e", applescript);
        pb.directory(workingDir.toFile());
        pb.inheritIO();
        return pb.start();
    }

    private String detectMacTerminal() {
        if (isOnPath("kitty")) return "kitty";
        if (isOnPath("alacritty")) return "alacritty";
        if (isAppInstalled("iTerm")) return "iTerm2";
        return "Terminal.app";
    }

    private boolean isAppInstalled(String appName) {
        return Path.of("/Applications", appName + ".app").toFile().isDirectory();
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private boolean isOnPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, binary).canExecute()) return true;
        }
        return false;
    }

    /**
     * Build a bash -c wrapper that:
     * 1. Runs the command, capturing exit code
     * 2. Always drops to an interactive shell afterwards so the terminal stays open
     * 3. On failure, prints an error message before the shell
     */
    private String buildBashWrapperCommand(List<String> command) {
        String cmdStr = joinShellCommand(command);
        // Always keep the terminal open by exec-ing an interactive shell after the
        // command finishes. On failure, print diagnostic info first.
        return cmdStr + "; __kompile_ec=$?; "
                + "if [ $__kompile_ec -ne 0 ]; then "
                + "echo ''; "
                + "echo \"[kompile] Command failed: " + cmdStr.replace("'", "'\\''") + "\"; "
                + "echo \"[kompile] Exit code: $__kompile_ec\"; "
                + "echo ''; "
                + "fi; "
                + "exec $SHELL";
    }

    private String joinShellCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(shellEscape(command.get(i)));
        }
        return sb.toString();
    }

    private String shellEscape(String s) {
        if (s.matches("[a-zA-Z0-9_/.:=-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String escapeAppleScript(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
