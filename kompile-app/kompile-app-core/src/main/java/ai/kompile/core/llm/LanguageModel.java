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

package ai.kompile.core.llm;


import org.springframework.ai.chat.model.ChatResponse; // Using Spring AI's ChatClient
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

public interface LanguageModel {

    String generateResponse(String userQuery, List<String> context);

    // For tool calling, Spring AI ChatClient handles this more directly
    // The LanguageModel interface might wrap a ChatClient
    ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context);

    // If you want to manage tool definitions manually for MCP listing (though Spring AI MCP server does this for @AiTool)
    // List<McpToolDefinition> getAvailableTools();
}