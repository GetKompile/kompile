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
 * A consumer of {@link SubprocessLogEvent}s.
 *
 * <p>Implementations are registered with the {@link SubprocessLogBus} — either
 * statically as Spring beans (e.g. the central {@code ~/.kompile/logs} writer)
 * or dynamically at runtime (e.g. a crawl registering a per-job listener that
 * republishes subprocess lines to its SSE stream while a step runs, then
 * unregisters when the step ends).</p>
 *
 * <p>Implementations MUST be non-blocking and must never throw — the bus
 * fans out on the subprocess reader threads, so a slow or throwing sink would
 * stall log draining for every consumer.</p>
 */
@FunctionalInterface
public interface SubprocessLogSink {

    /**
     * Receive one subprocess log/lifecycle event.
     *
     * @param event the event; never {@code null}
     */
    void onLog(SubprocessLogEvent event);
}
