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

package ai.kompile.cli.common.chat.sources;

import java.time.Instant;

public record ChatTurn(String role, String content, Instant timestamp) {

    public ChatTurn(String role, String content) {
        this(role, content, null);
    }

    public boolean isUser() {
        return role != null && ("user".equalsIgnoreCase(role) || "human".equalsIgnoreCase(role));
    }

    public boolean isAssistant() {
        return role != null && (
                "assistant".equalsIgnoreCase(role)
                        || "ai".equalsIgnoreCase(role)
                        || "model".equalsIgnoreCase(role)
                        || "bot".equalsIgnoreCase(role));
    }
}
