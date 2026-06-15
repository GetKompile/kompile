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

package ai.kompile.core.kgembedding;

import java.util.List;

/**
 * Result of knowledge graph embedding training.
 *
 * @param success Whether training completed successfully
 * @param entitiesCount Number of entities with embeddings
 * @param relationsCount Number of relations with embeddings
 * @param triplesCount Number of training triples
 * @param epochsCompleted Number of epochs completed
 * @param finalLoss Final loss value
 * @param lossHistory List of loss values per epoch
 * @param trainingTimeMs Total training time in milliseconds
 * @param errorMessage Error message if training failed
 */
public record TrainingResult(
        boolean success,
        int entitiesCount,
        int relationsCount,
        int triplesCount,
        int epochsCompleted,
        double finalLoss,
        List<Double> lossHistory,
        long trainingTimeMs,
        String errorMessage
) {
    /**
     * Creates a successful training result.
     */
    public static TrainingResult success(
            int entitiesCount,
            int relationsCount,
            int triplesCount,
            int epochsCompleted,
            double finalLoss,
            List<Double> lossHistory,
            long trainingTimeMs
    ) {
        return new TrainingResult(
                true,
                entitiesCount,
                relationsCount,
                triplesCount,
                epochsCompleted,
                finalLoss,
                lossHistory,
                trainingTimeMs,
                null
        );
    }

    /**
     * Creates a failed training result.
     */
    public static TrainingResult failure(String errorMessage) {
        return new TrainingResult(
                false,
                0,
                0,
                0,
                0,
                Double.NaN,
                List.of(),
                0,
                errorMessage
        );
    }

    /**
     * Creates a cancelled training result.
     */
    public static TrainingResult cancelled(int epochsCompleted, double lastLoss) {
        return new TrainingResult(
                false,
                0,
                0,
                0,
                epochsCompleted,
                lastLoss,
                List.of(),
                0,
                "Training was cancelled"
        );
    }

    /**
     * Returns the average triples processed per second.
     */
    public double triplesPerSecond() {
        if (trainingTimeMs == 0 || triplesCount == 0 || epochsCompleted == 0) {
            return 0.0;
        }
        long totalTriples = (long) triplesCount * epochsCompleted;
        return totalTriples * 1000.0 / trainingTimeMs;
    }

    /**
     * Returns training time as a formatted string.
     */
    public String trainingTimeString() {
        long seconds = trainingTimeMs / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) {
            return String.format("%dm %ds", minutes, seconds);
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    /**
     * Returns a summary string.
     */
    public String summary() {
        if (!success) {
            return "Training failed: " + errorMessage;
        }
        return String.format(
                "Trained %d entities, %d relations on %d triples over %d epochs. " +
                        "Final loss: %.4f, Time: %s (%.1f triples/sec)",
                entitiesCount,
                relationsCount,
                triplesCount,
                epochsCompleted,
                finalLoss,
                trainingTimeString(),
                triplesPerSecond()
        );
    }
}
