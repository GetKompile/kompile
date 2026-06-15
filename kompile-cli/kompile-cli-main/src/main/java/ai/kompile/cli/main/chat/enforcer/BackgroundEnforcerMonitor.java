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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background daemon thread that monitors an agent's terminal output (captured via
 * {@code script} command to a log file) and checks for enforcer rule violations.
 * <p>
 * When violations are detected the monitor writes a JSON object to an interrupt file
 * that the {@code enforcer_check} MCP tool reads. Multiple violations accumulate as
 * a JSON array. The monitor uses {@link RandomAccessFile} to tail the log file
 * efficiently, seeking to the last read position on each poll.
 * </p>
 *
 * <h3>Interrupt file format</h3>
 * Each entry in the interrupt file JSON array:
 * <pre>
 * {
 *   "timestamp": "2026-06-14T05:30:00Z",
 *   "violation": "Banned keyword detected: rm -rf",
 *   "rule": "BAN_CMD: rm -rf",
 *   "severity": "BLOCK",
 *   "acknowledged": false
 * }
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Construct with {@link EnforcerConfig}, log file, interrupt file and working dir</li>
 *   <li>Call {@link #start()} to begin background monitoring</li>
 *   <li>Call {@link #getViolations()} to retrieve detected violations</li>
 *   <li>Call {@link #acknowledgeAll()} to mark all violations as acknowledged</li>
 *   <li>Call {@link #shutdown()} to stop the thread</li>
 * </ol>
 */
public class BackgroundEnforcerMonitor {

    // ── ANSI escape sequence stripping ────────────────────────────────────────

    /**
     * Regex that strips ALL ANSI/VT escape sequences, including:
     * - CSI sequences: ESC [ ... final-byte
     * - OSC sequences: ESC ] ... ST|BEL
     * - Charset designators: ESC ( 0
     * - Single-char intermediates: ESC > = <
     * - String terminator: ESC \
     */
    private static final String ANSI_REGEX =
            "\033\\[[0-9;?]*[a-zA-Z]"
            + "|\033\\].*?(?:\033\\\\|\007)"
            + "|\033[()][0-9A-B]"
            + "|\033[>=<]"
            + "|\033\\\\";

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Polling interval in milliseconds. */
    private static final long POLL_INTERVAL_MS = 500;

    /** Thread name for the background monitor. */
    private static final String THREAD_NAME = "enforcer-bg-monitor";

    /** Permission key that the {@code enforcer_check} MCP tool checks. */
    private static final String PERMISSION_KEY = "enforcer_check";

    // ── Fields ────────────────────────────────────────────────────────────────

    private final EnforcerConfig config;
    private final Path outputLogFile;
    private final Path interruptFile;
    private final Path workingDir;

    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    /** Current read offset into the log file. */
    private long offset = 0;

    /** Accumulated partial line from the previous read (may not end with newline). */
    private String partialChunk = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Create a new background enforcer monitor.
     *
     * @param config        the enforcer configuration containing banned keywords, commands, and tools
     * @param outputLogFile the file being written by the {@code script} command in real-time
     * @param interruptFile the file to write violations into (read by {@code enforcer_check} MCP tool)
     * @param workingDir    the agent's working directory (used for context)
     */
    public BackgroundEnforcerMonitor(EnforcerConfig config,
                                     Path outputLogFile,
                                     Path interruptFile,
                                     Path workingDir) {
        this.config = config;
        this.outputLogFile = outputLogFile.toAbsolutePath().normalize();
        this.interruptFile = interruptFile.toAbsolutePath().normalize();
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the background monitoring thread. Idempotent — calling start() when
     * already running has no effect.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stop the background monitoring thread. Blocks until the thread exits or
     * 2 seconds have elapsed.
     */
    public void shutdown() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private void runLoop() {
        while (running.get()) {
            try {
                pollOnce();
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Best-effort monitoring — do not crash the thread on transient errors.
            }
        }
    }

    private void pollOnce() throws IOException {
        if (!Files.exists(outputLogFile)) {
            return;
        }

        long size = Files.size(outputLogFile);

        // Handle log rotation / truncation.
        if (size < offset) {
            offset = 0;
            partialChunk = "";
        }
        if (size == offset) {
            return;
        }

        // Read new bytes since last poll.
        byte[] newBytes = readBytes(outputLogFile, offset);
        if (newBytes.length == 0) {
            return;
        }
        offset += newBytes.length;

        String raw = partialChunk + new String(newBytes, StandardCharsets.UTF_8);

        // Split into lines; the last element may be a partial line if the chunk
        // does not end with a newline character.
        String[] lines = raw.split("\\R", -1);
        int completeCount = lines.length;
        boolean endsWithNewline = raw.endsWith("\n") || raw.endsWith("\r");
        if (!endsWithNewline && lines.length > 0) {
            completeCount = lines.length - 1;
            partialChunk = lines[lines.length - 1];
        } else {
            partialChunk = "";
        }

        for (int i = 0; i < completeCount; i++) {
            String line = lines[i];
            if (!line.isBlank()) {
                checkLine(stripAnsi(line));
            }
        }
    }

    // ── Rule checking ─────────────────────────────────────────────────────────

    /**
     * Check a single (already ANSI-stripped) line against the configured rules and
     * write a violation entry to the interrupt file if any rule matches.
     */
    private void checkLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);

        // Check bannedKeywords
        for (String keyword : config.getBannedKeywords()) {
            if (keyword == null || keyword.isBlank()) continue;
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                writeViolation(
                        "Banned keyword detected: " + keyword,
                        "BAN: " + keyword,
                        "BLOCK");
                return; // One violation per line is sufficient.
            }
        }

        // Check bannedCommands
        for (String cmd : config.getBannedCommands()) {
            if (cmd == null || cmd.isBlank()) continue;
            if (lower.contains(cmd.toLowerCase(Locale.ROOT))) {
                writeViolation(
                        "Banned command detected: " + cmd,
                        "BAN_CMD: " + cmd,
                        "BLOCK");
                return;
            }
        }

        // Check bannedTools — look for the tool name appearing in the output.
        for (String tool : config.getBannedTools()) {
            if (tool == null || tool.isBlank()) continue;
            if (lower.contains(tool.toLowerCase(Locale.ROOT))) {
                writeViolation(
                        "Banned tool detected: " + tool,
                        "BAN_TOOL: " + tool,
                        "BLOCK");
                return;
            }
        }
    }

    // ── Interrupt file I/O ────────────────────────────────────────────────────

    /**
     * Append a violation entry to the interrupt file. Creates the file (and parent
     * directories) if it does not exist. If the file already contains a JSON array,
     * the new entry is appended to that array.
     */
    private void writeViolation(String violation, String rule, String severity) {
        try {
            Files.createDirectories(interruptFile.getParent());

            ArrayNode array;
            if (Files.exists(interruptFile) && Files.size(interruptFile) > 0) {
                try {
                    JsonNode existing = objectMapper.readTree(interruptFile.toFile());
                    if (existing.isArray()) {
                        array = (ArrayNode) existing;
                    } else {
                        // Unexpected format — start fresh.
                        array = objectMapper.createArrayNode();
                    }
                } catch (Exception e) {
                    // Corrupt file — start fresh.
                    array = objectMapper.createArrayNode();
                }
            } else {
                array = objectMapper.createArrayNode();
            }

            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("timestamp", Instant.now().toString());
            entry.put("violation", violation);
            entry.put("rule", rule);
            entry.put("severity", severity);
            entry.put("acknowledged", false);
            array.add(entry);

            objectMapper.writeValue(interruptFile.toFile(), array);
        } catch (IOException e) {
            System.err.println("[enforcer-bg-monitor] Warning: could not write interrupt file "
                    + interruptFile + ": " + e.getMessage());
        }
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    /**
     * Read the interrupt file and return all recorded violations.
     * Returns an empty list if the file does not exist or cannot be parsed.
     */
    public List<ObjectNode> getViolations() {
        List<ObjectNode> result = new ArrayList<>();
        if (!Files.exists(interruptFile)) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(interruptFile.toFile());
            if (!root.isArray()) {
                return result;
            }
            for (JsonNode node : root) {
                if (node.isObject()) {
                    result.add((ObjectNode) node);
                }
            }
        } catch (IOException e) {
            System.err.println("[enforcer-bg-monitor] Warning: could not read interrupt file "
                    + interruptFile + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Set {@code acknowledged = true} on all entries in the interrupt file.
     * This signals to the {@code enforcer_check} MCP tool that violations have
     * been processed.
     */
    public void acknowledgeAll() {
        if (!Files.exists(interruptFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(interruptFile.toFile());
            if (!root.isArray()) {
                return;
            }
            ArrayNode array = (ArrayNode) root;
            for (JsonNode node : array) {
                if (node.isObject()) {
                    ((ObjectNode) node).put("acknowledged", true);
                }
            }
            objectMapper.writeValue(interruptFile.toFile(), array);
        } catch (IOException e) {
            System.err.println("[enforcer-bg-monitor] Warning: could not acknowledge violations in "
                    + interruptFile + ": " + e.getMessage());
        }
    }

    /**
     * Returns the permission key used by the {@code enforcer_check} MCP tool.
     */
    public String permissionKey() {
        return PERMISSION_KEY;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Returns the default interrupt file path for a given session ID.
     * Located at {@code ~/.kompile/sessions/<sessionId>/enforcer-interrupt.json}.
     *
     * @param sessionId the session identifier
     * @return absolute path to the interrupt file
     */
    public static Path interruptFilePath(String sessionId) {
        return sessionDir(sessionId).resolve("enforcer-interrupt.json");
    }

    /**
     * Returns the session directory for a given session ID.
     *
     * @param sessionId the session identifier
     * @return absolute path to the session directory
     */
    public static Path sessionDir(String sessionId) {
        return Path.of(System.getProperty("user.home"))
                .resolve(".kompile")
                .resolve("sessions")
                .resolve(sessionId);
    }

    // ── Internal utilities ────────────────────────────────────────────────────

    /**
     * Read bytes from {@code file} starting at {@code startOffset} using
     * {@link RandomAccessFile} for efficient seeking without re-reading the whole file.
     */
    private static byte[] readBytes(Path file, long startOffset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            if (startOffset >= size) {
                return new byte[0];
            }
            raf.seek(startOffset);
            int toRead = (int) Math.min(size - startOffset, Integer.MAX_VALUE);
            byte[] buf = new byte[toRead];
            int read = 0;
            while (read < toRead) {
                int n = raf.read(buf, read, toRead - read);
                if (n == -1) break;
                read += n;
            }
            if (read == toRead) {
                return buf;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        }
    }

    /**
     * Strip ALL ANSI/VT escape sequences from a string.
     *
     * @param s raw terminal output (may contain ANSI escape codes)
     * @return clean text with all escape sequences removed
     */
    static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll(ANSI_REGEX, "");
    }
}
