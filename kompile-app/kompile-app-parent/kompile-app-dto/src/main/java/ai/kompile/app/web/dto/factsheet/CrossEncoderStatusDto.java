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
package ai.kompile.app.web.dto.factsheet;

public record CrossEncoderStatusDto(
    boolean rerankingEnabled,
    String rerankerType,
    String configuredModel,
    String configuredSource,
    String configuredArchiveId,
    Integer rerankTopK,
    boolean available,
    String modelFoundIn,  // "registry", "built-in", or null
    boolean matchesConfig,
    boolean loaded,       // true if the model has been loaded for inference
    String loadStatus     // "not_configured", "not_available", "available_not_loaded", "loaded", "disabled"
) {}
