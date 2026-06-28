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

package ai.kompile.app.subprocess;

/**
 * A managed subprocess that can be restarted by the general watchdog.
 *
 * <p>Launchers that manage a long-lived child process implement this interface
 * and self-register with {@link SubprocessRegistry#registerRestartHandler} on
 * start so the parent-side {@code SubprocessRssWatchdog} can trigger a restart
 * for <em>any</em> subprocess that exceeds the configured RSS threshold — without
 * hard-coding knowledge of individual launcher types.</p>
 *
 * <p>Implementations must be idempotent: {@link #requestRestart} may be called
 * concurrently with normal operations and must not corrupt launcher state.</p>
 */
public interface RestartableSubprocess {

    /**
     * Return the stable registry id used when this subprocess was registered via
     * {@link SubprocessRegistry#register(String, Process, String)}.
     *
     * @return non-null, non-blank subprocess id (e.g. {@code "embedding"}, {@code "serving"})
     */
    String getSubprocessId();

    /**
     * Initiate a restart of the subprocess.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Record the provided {@code reason} for diagnostics.</li>
     *   <li>Delegate to their normal crash/restart path so existing backoff,
     *       model-reload, and event-history recording are reused.</li>
     *   <li>Return quickly (fire-and-forget via a background thread) so the
     *       watchdog's scheduler thread is not blocked.</li>
     * </ol>
     *
     * @param reason human-readable explanation, e.g. "RSS 18432 MB exceeds limit 16384 MB"
     */
    void requestRestart(String reason);
}
