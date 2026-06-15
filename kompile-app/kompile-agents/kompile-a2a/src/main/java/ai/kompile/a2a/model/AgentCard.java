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

/**
 * A2A Agent Card - advertises an agent's identity, capabilities, and skills.
 * Served at {@code /.well-known/agent-card.json} per the A2A specification.
 *
 * @see <a href="https://a2a-protocol.org/latest/">A2A Protocol Specification</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentCard {

    private String name;
    private String description;
    private String version;
    private String url;
    private String protocolVersion;

    private AgentCapabilities capabilities;
    private List<AgentSkill> skills;

    private List<String> defaultInputModes;
    private List<String> defaultOutputModes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentCapabilities {
        @Builder.Default
        private boolean streaming = true;
        @Builder.Default
        private boolean pushNotifications = false;
        @Builder.Default
        private boolean stateTransitionHistory = false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentSkill {
        private String id;
        private String name;
        private String description;
        private List<String> tags;
        private List<String> examples;
        private List<String> inputModes;
        private List<String> outputModes;
    }
}
