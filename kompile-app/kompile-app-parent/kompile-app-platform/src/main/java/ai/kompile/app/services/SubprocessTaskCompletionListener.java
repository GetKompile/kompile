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

package ai.kompile.app.services;

/**
 * SPI a subprocess launcher uses to announce that a tracked task has finished.
 *
 * <p>This exists to invert what used to be a back-edge: the ingest launchers live in
 * {@code kompile-app-ingest-svc}, but the only consumer of task completion —
 * {@code ai.kompile.app.monitor.service.MonitorService}, which wakes chat monitors bound
 * to a task id — lives in {@code kompile-app-main}, one layer up. Launchers depend on this
 * interface; app-main supplies the implementation.
 *
 * <p>Injection is expected to be optional ({@code @Autowired(required = false)}): a persona
 * app that boots without the monitor subsystem simply has no bean here, and completion
 * notification is skipped. Implementations must not throw — callers invoke this from
 * completion hooks where a failure must never block the task.
 */
public interface SubprocessTaskCompletionListener {

    /**
     * @param taskId  id of the subprocess task that finished
     * @param success whether the task completed without error
     * @param summary human-readable one-line outcome, suitable for a chat wake-up message
     */
    void onTaskCompleted(String taskId, boolean success, String summary);
}
