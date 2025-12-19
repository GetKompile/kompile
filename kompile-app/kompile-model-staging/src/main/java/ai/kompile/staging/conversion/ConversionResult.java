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

package ai.kompile.staging.conversion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * Result of a model conversion operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversionResult {

    /**
     * Whether conversion was successful.
     */
    private boolean success;

    /**
     * Error message if conversion failed.
     */
    private String errorMessage;

    /**
     * Path to the converted SameDiff model file.
     */
    private Path outputModelPath;

    /**
     * Original format: "onnx", "tensorflow", "keras".
     */
    private String originalFormat;

    /**
     * SHA256 checksum of the output model.
     */
    private String checksum;

    /**
     * Number of operations in the converted model.
     */
    private int numOperations;

    /**
     * Number of variables in the converted model.
     */
    private int numVariables;

    /**
     * Conversion duration in milliseconds.
     */
    private long durationMs;

    /**
     * Warnings encountered during conversion (non-fatal).
     */
    private String[] warnings;

    /**
     * Create a successful result.
     */
    public static ConversionResult success(Path outputPath, String checksum,
                                           int numOps, int numVars, long durationMs) {
        return ConversionResult.builder()
                .success(true)
                .outputModelPath(outputPath)
                .checksum(checksum)
                .numOperations(numOps)
                .numVariables(numVars)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Create a failed result.
     */
    public static ConversionResult failure(String errorMessage) {
        return ConversionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
