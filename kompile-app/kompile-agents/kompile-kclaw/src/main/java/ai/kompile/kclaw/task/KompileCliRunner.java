/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs the kompile CLI's own agent via {@code kompile exec --json} as a subprocess and
 * collects its streamed JSONL output ({@code {type: session|text|tool|result|error}}).
 *
 * <p>The kompile binary is resolved from (in order): the configured path, {@code PATH},
 * {@code ~/.kompile/bin/kompile}, and the dev native-image target. If none is found, runs
 * fail gracefully with a clear message rather than throwing.
 */
@Slf4j
public class KompileCliRunner {

    private final ObjectMapper mapper;
    private final String configuredBinary;
    private final long timeoutMs;

    public KompileCliRunner(ObjectMapper mapper, String configuredBinary, long timeoutMs) {
        this.mapper = mapper;
        this.configuredBinary = configuredBinary;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 600_000L;
    }

    /** Outcome of a kompile exec run. */
    public record Result(boolean success, String output, String error) {}

    public Result run(String task, String model) {
        String bin = resolveBinary();
        if (bin == null) {
            return new Result(false, "",
                    "kompile CLI binary not found — set 'kompile.cli.binary-path', add 'kompile' to PATH, "
                            + "or run 'kompile install'.");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("exec");
        cmd.add("--json");
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        cmd.add(task);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            process.getOutputStream().close(); // no stdin

            // Drain stdout (JSONL) on a daemon thread so waitFor() can bound the run.
            List<String> lines = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (Exception e) {
                    log.debug("kompile exec reader error: {}", e.getMessage());
                }
            }, "kompile-exec-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(2000);
                ParsedOutput partial = parseOutput(mapper, lines);
                return new Result(false, partial.text(), "kompile exec timed out after " + timeoutMs + "ms");
            }
            reader.join(2000);

            int exit = process.exitValue();
            ParsedOutput parsed = parseOutput(mapper, lines);
            if (exit == 0) {
                return new Result(true, parsed.text(), null);
            }
            String err = parsed.error() != null ? parsed.error() : ("kompile exec exited with code " + exit);
            return new Result(false, parsed.text(), err);

        } catch (Exception e) {
            return new Result(false, "", "kompile exec failed: " + e.getMessage());
        }
    }

    /** Parsed view of a kompile {@code exec --json} stream. */
    record ParsedOutput(String text, String error) {}

    /**
     * Parse {@code kompile exec --json} JSONL lines into a final text + optional error.
     * Prefers the terminal {@code result} event's full text; otherwise concatenates {@code text}
     * deltas. Pure and side-effect free so the wire contract is unit-testable.
     */
    static ParsedOutput parseOutput(ObjectMapper mapper, List<String> lines) {
        StringBuilder deltas = new StringBuilder();
        String resultText = null;
        String error = null;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) != '{') {
                continue;
            }
            try {
                JsonNode n = mapper.readTree(line);
                switch (n.path("type").asText("")) {
                    case "text" -> deltas.append(n.path("text").asText(""));
                    case "result" -> resultText = n.path("text").asText(null);
                    case "error" -> error = n.path("message").asText(null);
                    default -> { /* session, tool, unknown — ignored for the final text */ }
                }
            } catch (Exception ignored) {
                // non-JSON line — skip
            }
        }
        String text = resultText != null ? resultText : deltas.toString();
        return new ParsedOutput(text, error);
    }

    private String resolveBinary() {
        if (isExecutable(configuredBinary)) {
            return configuredBinary;
        }
        String onPath = whichKompile();
        if (onPath != null) {
            return onPath;
        }
        String home = System.getProperty("user.home", "");
        String[] candidates = {
                home + "/.kompile/bin/kompile",
                System.getProperty("user.dir", ".") + "/kompile-cli/kompile-cli-main/target/kompile-cli-main"
        };
        for (String c : candidates) {
            if (isExecutable(c)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isExecutable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File f = new File(path);
        return f.isFile() && f.canExecute();
    }

    private static String whichKompile() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File candidate = new File(dir, "kompile");
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }
}
