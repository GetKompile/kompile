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

package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats a list of ChatMessage objects into a flat prompt string
 * using various chat template formats commonly used by open-source LLMs.
 */
@Service
public class ChatTemplateService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Format chat messages using the specified template name.
     */
    public String format(List<ChatMessage> messages, String templateName) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        String template = templateName != null ? templateName.toLowerCase() : "chatml";
        switch (template) {
            case "llama2":
                return formatLlama2(messages);
            case "vicuna":
                return formatVicuna(messages);
            case "alpaca":
                return formatAlpaca(messages);
            case "simple":
                return formatSimple(messages);
            case "chatml":
            default:
                return formatChatML(messages);
        }
    }

    /**
     * Get list of built-in template names.
     */
    public List<Map<String, String>> getBuiltinTemplates() {
        List<Map<String, String>> templates = new ArrayList<>();
        templates.add(Map.of("name", "chatml", "description", "ChatML format (<|im_start|>role). Used by Qwen, Mistral, and many others."));
        templates.add(Map.of("name", "llama2", "description", "Llama 2 chat format with [INST] tags. Used by Llama 2 chat models."));
        templates.add(Map.of("name", "vicuna", "description", "Vicuna format (USER:/ASSISTANT:). Used by Vicuna and similar models."));
        templates.add(Map.of("name", "alpaca", "description", "Alpaca instruction format (### Instruction/Response). Used by Alpaca-style models."));
        templates.add(Map.of("name", "simple", "description", "Simple role-prefixed format (System:/User:/Assistant:). Universal fallback."));
        return templates;
    }

    /**
     * Substitute {{variable}} placeholders in a template string.
     */
    public String substituteVariables(String template, Map<String, String> variables) {
        if (template == null || variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /**
     * Extract variable names from a template string ({{variableName}} patterns).
     */
    public List<String> extractVariables(String template) {
        if (template == null) return Collections.emptyList();
        Set<String> vars = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return new ArrayList<>(vars);
    }

    // ChatML: <|im_start|>role\ncontent<|im_end|>
    private String formatChatML(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append("<|im_start|>").append(msg.getRole()).append("\n");
            sb.append(msg.getContent()).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    // Llama 2: [INST] <<SYS>>\nsystem\n<</SYS>>\n\nuser [/INST] assistant
    private String formatLlama2(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        String systemPrompt = null;

        // Extract system message
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemPrompt = msg.getContent();
                break;
            }
        }

        boolean firstUser = true;
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.getRole())) continue;

            if ("user".equals(msg.getRole())) {
                sb.append("[INST] ");
                if (firstUser && systemPrompt != null) {
                    sb.append("<<SYS>>\n").append(systemPrompt).append("\n<</SYS>>\n\n");
                }
                sb.append(msg.getContent()).append(" [/INST] ");
                firstUser = false;
            } else if ("assistant".equals(msg.getRole())) {
                sb.append(msg.getContent()).append(" ");
            }
        }
        return sb.toString();
    }

    // Vicuna: USER: content\nASSISTANT: content
    private String formatVicuna(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            switch (msg.getRole()) {
                case "system":
                    sb.append(msg.getContent()).append("\n\n");
                    break;
                case "user":
                    sb.append("USER: ").append(msg.getContent()).append("\n");
                    break;
                case "assistant":
                    sb.append("ASSISTANT: ").append(msg.getContent()).append("\n");
                    break;
            }
        }
        sb.append("ASSISTANT: ");
        return sb.toString();
    }

    // Alpaca: ### Instruction:\ncontent\n### Response:\ncontent
    private String formatAlpaca(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            switch (msg.getRole()) {
                case "system":
                    sb.append(msg.getContent()).append("\n\n");
                    break;
                case "user":
                    sb.append("### Instruction:\n").append(msg.getContent()).append("\n\n");
                    break;
                case "assistant":
                    sb.append("### Response:\n").append(msg.getContent()).append("\n\n");
                    break;
            }
        }
        sb.append("### Response:\n");
        return sb.toString();
    }

    // Simple: System: content\nUser: content\nAssistant: content
    private String formatSimple(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String role = msg.getRole().substring(0, 1).toUpperCase() + msg.getRole().substring(1);
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        sb.append("Assistant: ");
        return sb.toString();
    }
}
