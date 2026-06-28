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
 * A single observable event emitted by a managed subprocess: a raw log line
 * (stdout/stderr) or a lifecycle transition (starting / ready / stopping / …).
 *
 * <p>This is the shared currency of {@link SubprocessLogBus}: every managed
 * subprocess publishes these, and any consumer (the central
 * {@code ~/.kompile/logs} writer, the crawl UI's SSE publisher, a CLI tail)
 * subscribes without knowing which launcher produced them. It is the
 * mechanism that gets a subprocess's <em>own logs in real time</em> to the
 * crawl job that owns its lifecycle.</p>
 *
 * @param subprocessId stable subprocess id (e.g. {@code "embedding"}, {@code "learning"})
 * @param runId        per-run id (one subprocess invocation), unique per start
 * @param jobId        owning crawl job id, or {@code null} when not started for a crawl
 * @param stream       {@link Stream} the line/event came from
 * @param level        log level — {@code INFO}/{@code WARN}/{@code ERROR}/{@code DEBUG}/{@code LIFECYCLE}
 * @param message      the raw log line or lifecycle message
 * @param timestampMs  wall-clock time the event was observed
 */
public record SubprocessLogEvent(
        String subprocessId,
        String runId,
        String jobId,
        Stream stream,
        String level,
        String message,
        long timestampMs) {

    /** Origin of the event. */
    public enum Stream {
        /** A line read from the subprocess stdout (non-structured). */
        STDOUT,
        /** A line read from the subprocess stderr. */
        STDERR,
        /** A lifecycle transition emitted by the launcher itself. */
        LIFECYCLE
    }

    /** Convenience factory for a stdout/stderr line. */
    public static SubprocessLogEvent line(String subprocessId, String runId, String jobId,
                                          Stream stream, String level, String message) {
        return new SubprocessLogEvent(subprocessId, runId, jobId, stream, level, message,
                System.currentTimeMillis());
    }

    /** Convenience factory for a lifecycle transition. */
    public static SubprocessLogEvent lifecycle(String subprocessId, String runId, String jobId,
                                               String message) {
        return new SubprocessLogEvent(subprocessId, runId, jobId, Stream.LIFECYCLE, "LIFECYCLE",
                message, System.currentTimeMillis());
    }
}
