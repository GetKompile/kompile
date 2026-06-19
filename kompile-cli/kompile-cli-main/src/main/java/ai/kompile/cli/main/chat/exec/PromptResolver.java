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

package ai.kompile.cli.main.chat.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Resolves the prompt for a non-interactive {@code kompile exec} run.
 *
 * <p>Precedence mirrors {@code codex exec} / {@code opencode run}:
 * <ul>
 *   <li>Positional arguments are joined with single spaces and used as the prompt.</li>
 *   <li>If there are no positional args, or the single argument is {@code "-"},
 *       the prompt is read from stdin (so {@code echo "hi" | kompile exec} works).</li>
 * </ul>
 *
 * <p>Kept free of CLI/IO side effects (other than the explicit stdin argument)
 * so the resolution rules are unit-testable.
 */
public final class PromptResolver {

    /** Sentinel argument meaning "read the prompt from stdin". */
    public static final String STDIN_SENTINEL = "-";

    private PromptResolver() {}

    /**
     * Resolve the prompt from CLI args, falling back to {@code stdin}.
     *
     * @param args  positional prompt arguments (may be {@code null}/empty)
     * @param stdin stream to read when args are empty or {@code "-"} (may be {@code null})
     * @return the resolved, trimmed prompt (never blank)
     * @throws IOException              if reading stdin fails
     * @throws IllegalArgumentException if no prompt could be resolved
     */
    public static String resolve(List<String> args, InputStream stdin) throws IOException {
        String fromArgs = joinArgs(args);
        if (fromArgs != null && !fromArgs.equals(STDIN_SENTINEL)) {
            return fromArgs;
        }
        String fromStdin = readAll(stdin);
        if (fromStdin != null && !fromStdin.isBlank()) {
            return fromStdin.strip();
        }
        throw new IllegalArgumentException(
                "No prompt provided. Pass it as an argument (kompile exec \"...\") "
                        + "or pipe it on stdin (echo \"...\" | kompile exec).");
    }

    /** Join non-blank args with single spaces; returns {@code null} when there is nothing usable. */
    static String joinArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", args).strip();
        return joined.isEmpty() ? null : joined;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
