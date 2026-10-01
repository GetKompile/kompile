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
 * Stops a child process without throwing away output it has already written.
 *
 * <p>On Linux, {@link Process#destroy()} and {@link Process#destroyForcibly()} close the parent's ends
 * of the child's pipes right after signalling it. Whatever is still in a pipe is lost — a COMPLETED or
 * FAILED report written just before the signal, the child's last log lines — and a reader gets
 * "Stream closed" in its place. Signalling through {@link Process#toHandle()} leaves the pipes open:
 * the readers read on to the end, and once the child exits the JDK keeps what they had not read yet.</p>
 */
public final class SubprocessSignals {

    private SubprocessSignals() {
    }

    /** Asks the child to stop: SIGTERM on Unix. */
    public static void terminate(Process process) {
        process.toHandle().destroy();
    }

    /** Stops the child: SIGKILL on Unix. */
    public static void kill(Process process) {
        process.toHandle().destroyForcibly();
    }
}
