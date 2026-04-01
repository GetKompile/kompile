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
package ai.kompile.core.graphbuilder;

/**
 * Progress callback record for graph building operations.
 *
 * @param jobId the job identifier
 * @param totalChunks total chunks to process
 * @param processedChunks chunks processed so far
 * @param proposalsCreated proposals created so far
 * @param status current status
 * @param errorMessage error message if failed
 */
public record BuildProgress(
        String jobId,
        int totalChunks,
        int processedChunks,
        int proposalsCreated,
        Status status,
        String errorMessage
) {
    public enum Status {
        STARTING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Calculate progress percentage.
     */
    public int progressPercent() {
        if (totalChunks <= 0) return 0;
        return (int) ((processedChunks * 100.0) / totalChunks);
    }

    /**
     * Create a starting progress.
     */
    public static BuildProgress starting(String jobId, int totalChunks) {
        return new BuildProgress(jobId, totalChunks, 0, 0, Status.STARTING, null);
    }

    /**
     * Create a processing progress.
     */
    public static BuildProgress processing(String jobId, int totalChunks, int processedChunks, int proposalsCreated, String message) {
        return new BuildProgress(jobId, totalChunks, processedChunks, proposalsCreated, Status.PROCESSING, message);
    }

    /**
     * Create a completed progress.
     */
    public static BuildProgress completed(String jobId, int totalChunks, int proposalsCreated) {
        return new BuildProgress(jobId, totalChunks, totalChunks, proposalsCreated, Status.COMPLETED, null);
    }

    /**
     * Create a failed progress.
     */
    public static BuildProgress failed(String jobId, int processedChunks, String errorMessage) {
        return new BuildProgress(jobId, 0, processedChunks, 0, Status.FAILED, errorMessage);
    }

    /**
     * Create a cancelled progress.
     */
    public static BuildProgress cancelled(String jobId, int processedChunks, int proposalsCreated) {
        return new BuildProgress(jobId, 0, processedChunks, proposalsCreated, Status.CANCELLED, "Job cancelled");
    }
}
