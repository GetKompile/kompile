/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.openclaw.model;

import ai.kompile.react.model.TokenUsage;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response from an OpenClaw agent interaction.
 */
@Data
@Builder
public class OpenClawResponse {

    private String response;

    private String sessionKey;

    private String agentId;

    private TokenUsage tokenUsage;

    @Builder.Default
    private boolean success = true;

    private String error;

    private Instant timestamp;

    private List<String> toolCalls;

    private Map<String, Object> metadata;

    public static OpenClawResponse of(String response, String sessionKey) {
        return OpenClawResponse.builder()
                .response(response)
                .sessionKey(sessionKey)
                .timestamp(Instant.now())
                .build();
    }

    public static OpenClawResponse error(String error) {
        return OpenClawResponse.builder()
                .success(false)
                .error(error)
                .timestamp(Instant.now())
                .build();
    }
}
