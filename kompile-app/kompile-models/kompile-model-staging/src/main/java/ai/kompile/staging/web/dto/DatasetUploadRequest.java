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

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for uploading a dataset for training.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetUploadRequest {
    private String name;
    private String format; // JSONL, CSV, PARQUET, TEXT
    private String task; // CAUSAL_LM, SEQ2SEQ, CLASSIFICATION, PREFERENCE
    private String inputColumn;
    private String outputColumn;
    private String chosenColumn;
    private String rejectedColumn;
    @Builder.Default
    private double trainSplit = 0.9;
}
