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

package ai.kompile.cli.main.chat.terminal;

import java.util.List;

/**
 * L0 seam for allocating a pseudo-terminal around an agent subprocess.
 *
 * <p>The default {@link ScriptPtyProvider} wraps the command with {@code script(1)}
 * (today's behavior across the CLI). Keeping this behind an interface leaves room for
 * a pty4j-backed provider later (design WP14) without touching call sites.</p>
 *
 * <p>Node.js-based agents (OpenCode, Codex, etc.) fully buffer stdout when writing to a
 * pipe — without a PTY, no output arrives until the process exits. A PTY forces
 * line-buffered output so we can stream events.</p>
 */
public interface PtyProvider {

    /**
     * Wrap {@code command} so it runs under a PTY sized to {@code dims}.
     *
     * @param command the raw agent command (binary + args)
     * @param dims    desired terminal dimensions, or {@code null} to inherit the
     *                kernel-assigned size
     * @return the wrapped command, or a copy of {@code command} unchanged when PTY
     *         allocation is unavailable (e.g. Windows, or {@code script} not found)
     */
    List<String> wrap(List<String> command, PtyDims dims);

    /** @return whether this provider can currently allocate a PTY. */
    boolean available();

    /** Wrap without forcing dimensions (inherit the kernel-assigned size). */
    default List<String> wrap(List<String> command) {
        return wrap(command, null);
    }
}
