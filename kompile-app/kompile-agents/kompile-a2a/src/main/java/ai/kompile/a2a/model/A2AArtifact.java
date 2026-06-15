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

package ai.kompile.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2A Artifact - a packaged response from an agent containing one or more parts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class A2AArtifact {

    private String name;
    private String description;
    private List<A2AMessage.Part> parts;
    private Integer index;
    private Boolean append;
    private Boolean lastChunk;
    private Map<String, Object> metadata;

    /**
     * Create a simple text artifact.
     */
    public static A2AArtifact textArtifact(String name, String text) {
        return A2AArtifact.builder()
                .name(name)
                .parts(List.of(A2AMessage.Part.text(text)))
                .build();
    }
}
