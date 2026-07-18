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

import ai.kompile.modelmanager.registry.AudioSynthesisConfig;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for staging a new model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageModelRequest {
    private String source;
    private String repository;
    private String modelId;
    @JsonAlias("modelType")
    private String type;
    @Builder.Default
    private String format = "onnx";
    private String revision;
    @JsonAlias("token")
    private String authToken;
    private String tokenizerUrl;
    private AudioSynthesisConfig audioSynthesis;
    private Map<String, String> files;
    @Builder.Default
    private String outputFormat = "model";
    private String targetProfile;
    @Builder.Default
    private String quantizationProfile = "none";
    private String targetSoc;
}
