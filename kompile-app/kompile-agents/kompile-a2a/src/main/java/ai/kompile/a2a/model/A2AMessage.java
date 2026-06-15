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
 * A2A Message - container for parts exchanged between agents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class A2AMessage {

    private String role;
    private List<Part> parts;
    private Map<String, Object> metadata;

    /**
     * Create a user message with text content.
     */
    public static A2AMessage userText(String text) {
        return A2AMessage.builder()
                .role("user")
                .parts(List.of(Part.text(text)))
                .build();
    }

    /**
     * Create an agent message with text content.
     */
    public static A2AMessage agentText(String text) {
        return A2AMessage.builder()
                .role("agent")
                .parts(List.of(Part.text(text)))
                .build();
    }

    /**
     * A2A Part - polymorphic content unit within a message or artifact.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Part {
        private String type;
        private String text;
        private FileContent file;
        private Map<String, Object> data;
        private Map<String, Object> metadata;

        public static Part text(String text) {
            return Part.builder().type("text").text(text).build();
        }

        public static Part file(String name, String mimeType, String uri) {
            return Part.builder()
                    .type("file")
                    .file(FileContent.builder().name(name).mimeType(mimeType).uri(uri).build())
                    .build();
        }

        public static Part data(Map<String, Object> data) {
            return Part.builder().type("data").data(data).build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileContent {
        private String name;
        private String mimeType;
        private String uri;
        private String bytes;
    }
}
