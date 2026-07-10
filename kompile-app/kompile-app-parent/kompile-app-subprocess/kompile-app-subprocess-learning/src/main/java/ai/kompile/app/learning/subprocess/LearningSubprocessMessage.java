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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Polymorphic message hierarchy written by the learning subprocess to its
 * (original) stdout, prefixed with {@link #MESSAGE_PREFIX}.
 *
 * <p>The launcher reads lines from the subprocess's stdout, strips the prefix,
 * and deserialises the remaining JSON as one of these subtypes.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LearningSubprocessMessage.Progress.class,  name = "PROGRESS"),
        @JsonSubTypes.Type(value = LearningSubprocessMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = LearningSubprocessMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = LearningSubprocessMessage.Failed.class,    name = "FAILED"),
})
public sealed interface LearningSubprocessMessage
        permits LearningSubprocessMessage.Progress,
                LearningSubprocessMessage.Heartbeat,
                LearningSubprocessMessage.Completed,
                LearningSubprocessMessage.Failed {

    /** Prefix that identifies a structured message line on subprocess stdout. */
    String MESSAGE_PREFIX = "LEARNING_MSG:";

    /** Per-epoch progress update. */
    record Progress(String type,
                    int epoch,
                    int totalEpochs,
                    double loss,
                    double progressPercent) implements LearningSubprocessMessage {
        public Progress(int epoch, int totalEpochs, double loss, double progressPercent) {
            this("PROGRESS", epoch, totalEpochs, loss, progressPercent);
        }
    }

    /** Periodic heartbeat so the watchdog knows the process is still alive. */
    record Heartbeat(String type,
                     long timestampMs) implements LearningSubprocessMessage {
        public Heartbeat(long timestampMs) {
            this("HEARTBEAT", timestampMs);
        }
    }

    /** Training completed successfully. */
    record Completed(String type,
                     double finalLoss,
                     String outputPath,
                     int entities,
                     int relations) implements LearningSubprocessMessage {
        public Completed(double finalLoss, String outputPath, int entities, int relations) {
            this("COMPLETED", finalLoss, outputPath, entities, relations);
        }
    }

    /** Training failed (OOM, exception, cancellation). */
    record Failed(String type,
                  String reason) implements LearningSubprocessMessage {
        public Failed(String reason) {
            this("FAILED", reason);
        }
    }
}
