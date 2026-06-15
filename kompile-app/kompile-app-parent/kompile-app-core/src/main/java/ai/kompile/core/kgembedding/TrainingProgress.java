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

/**
 * Progress information during knowledge graph embedding training.
 * Used for real-time updates via WebSocket or callbacks.
 *
 * @param epoch Current epoch number (1-based)
 * @param totalEpochs Total number of epochs
 * @param loss Current loss value for this epoch
 * @param batchesCompleted Number of batches completed in current epoch
 * @param totalBatches Total batches in current epoch
 * @param elapsedMs Time elapsed since training started (milliseconds)
 * @param estimatedRemainingMs Estimated time remaining (milliseconds)
 * @param triplesPerSecond Training throughput (triples processed per second)
 */
public record TrainingProgress(
        int epoch,
        int totalEpochs,
        double loss,
        int batchesCompleted,
        int totalBatches,
        long elapsedMs,
        long estimatedRemainingMs,
        double triplesPerSecond
) {
    /**
     * Creates a basic progress record with just epoch and loss.
     */
    public TrainingProgress(int epoch, int totalEpochs, double loss) {
        this(epoch, totalEpochs, loss, 0, 0, 0, 0, 0);
    }

    /**
     * Returns the progress as a percentage (0.0 to 100.0).
     */
    public double progressPercent() {
        if (totalEpochs == 0) return 0.0;
        double epochProgress = (epoch - 1.0) / totalEpochs;
        if (totalBatches > 0) {
            epochProgress += (1.0 / totalEpochs) * ((double) batchesCompleted / totalBatches);
        }
        return epochProgress * 100.0;
    }

    /**
     * Returns true if this is the final epoch.
     */
    public boolean isFinalEpoch() {
        return epoch >= totalEpochs;
    }

    /**
     * Returns a human-readable progress string.
     */
    public String toProgressString() {
        return String.format("Epoch %d/%d (%.1f%%) - Loss: %.4f",
                epoch, totalEpochs, progressPercent(), loss);
    }

    /**
     * Returns elapsed time as a formatted string.
     */
    public String elapsedTimeString() {
        long seconds = elapsedMs / 1000;
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
        return String.format("%dh %dm", hours, minutes);
    }

    /**
     * Returns estimated remaining time as a formatted string.
     */
    public String remainingTimeString() {
        if (estimatedRemainingMs <= 0) {
            return "calculating...";
        }
        long seconds = estimatedRemainingMs / 1000;
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
        return String.format("%dh %dm", hours, minutes);
    }
}
