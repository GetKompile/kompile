/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.learning.subprocess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Writes structured {@link LearningSubprocessMessage} lines to the subprocess's
 * <em>original</em> stdout (captured by the parent launcher before the subprocess
 * redirects {@code System.out} to {@code System.err}).
 *
 * <p>This mirrors the {@code TrainingSubprocessProgressReporter} pattern: all
 * ND4J / Spring logging goes to stderr; the parent reads only {@code LEARNING_MSG:}
 * lines from stdout, guaranteeing clean round-trips.</p>
 */
public class LearningSubprocessProgressReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintStream out;
    private final ScheduledExecutorService heartbeatExecutor;

    public LearningSubprocessProgressReporter(PrintStream originalStdout) {
        this.out = originalStdout;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "learning-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start periodic heartbeat at the given interval (milliseconds). */
    public void startHeartbeat(long intervalMs) {
        heartbeatExecutor.scheduleAtFixedRate(
                () -> emit(new LearningSubprocessMessage.Heartbeat(System.currentTimeMillis())),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stop the heartbeat scheduler. */
    public void stopHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    /** Report per-epoch progress. */
    public void reportProgress(int epoch, int totalEpochs, double loss) {
        double pct = totalEpochs > 0 ? (epoch * 100.0 / totalEpochs) : 0.0;
        emit(new LearningSubprocessMessage.Progress(epoch, totalEpochs, loss, pct));
    }

    /** Report successful completion. */
    public void reportCompleted(double finalLoss, String outputPath, int entities, int relations) {
        emit(new LearningSubprocessMessage.Completed(finalLoss, outputPath, entities, relations));
    }

    /** Report a terminal failure. */
    public void reportFailed(String reason) {
        emit(new LearningSubprocessMessage.Failed(reason));
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void emit(LearningSubprocessMessage msg) {
        try {
            out.println(LearningSubprocessMessage.MESSAGE_PREFIX + MAPPER.writeValueAsString(msg));
            out.flush();
        } catch (JsonProcessingException e) {
            // Non-fatal: fall back to raw text so the parent can still detect errors
            out.println(LearningSubprocessMessage.MESSAGE_PREFIX
                    + "{\"type\":\"FAILED\",\"reason\":\"serialisation error: " + e.getMessage() + "\"}");
            out.flush();
        }
    }
}
