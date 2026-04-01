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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.llm.chat.LLMChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LLMChat implementation that uses locally installed CLI agents (Claude Code, Codex, Gemini CLI).
 * <p>
 * This provides LLM capabilities using CLI-based agents when no API-based LLM is configured.
 * It executes the agent as a subprocess and captures the output.
 * </p>
 * <p>
 * Priority: Loaded after API-based LLMChat implementations but before NoOpLLMChat.
 * Only activated when AgentRegistryService is available and has available agents.
 * </p>
 */
@Service("cliAgentLLMChat")
@ConditionalOnBean(AgentRegistryService.class)
@Order(100) // After API-based implementations (default), before NoOp
public class CliAgentLLMChat implements LLMChat {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLLMChat.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final AgentRegistryService agentRegistryService;
    private volatile AgentProvider cachedAgent;

    public CliAgentLLMChat(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;

        // Log initialization
        if (agentRegistryService.hasAvailableAgents()) {
            AgentProvider defaultAgent = agentRegistryService.getDefaultAgent().orElse(null);
            log.info("CliAgentLLMChat initialized with {} available CLI agents. Default: {}",
                    agentRegistryService.getAvailableAgentCount(),
                    defaultAgent != null ? defaultAgent.getDisplayName() : "none");
        } else {
            log.info("CliAgentLLMChat initialized but no CLI agents are available");
        }
    }

    /**
     * Check if this LLMChat implementation is functional.
     */
    public boolean isAvailable() {
        return agentRegistryService.hasAvailableAgents();
    }

    /**
     * Get the currently active agent.
     */
    private AgentProvider getActiveAgent() {
        if (cachedAgent == null || !cachedAgent.isAvailable()) {
            cachedAgent = agentRegistryService.getDefaultAgent().orElse(null);
        }
        return cachedAgent;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new CliAgentRequestSpec(this, null);
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return new CliAgentRequestSpec(this, content);
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        // Extract user content from prompt
        String content = prompt.getContents();
        return new CliAgentRequestSpec(this, content);
    }

    @Override
    public Builder mutate() {
        return new CliAgentBuilder(this);
    }

    /**
     * Execute the CLI agent with the given prompt.
     */
    String executeAgent(String userMessage, String systemMessage) {
        AgentProvider agent = getActiveAgent();
        if (agent == null) {
            log.warn("No CLI agent available for execution");
            return "Error: No CLI agent available. Please install Claude Code, Codex, or Gemini CLI.";
        }

        try {
            List<String> command = buildCommand(agent, userMessage, systemMessage);
            log.debug("Executing CLI agent '{}': {}", agent.getName(), String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse stream-json format if using Claude
                    if (agent.getName().equals("claude-cli")) {
                        String parsed = parseClaudeStreamJson(line);
                        if (parsed != null) {
                            output.append(parsed);
                        }
                    } else {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean completed = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("CLI agent '{}' timed out after {} seconds", agent.getName(), DEFAULT_TIMEOUT_SECONDS);
                return "Error: CLI agent timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("CLI agent '{}' exited with code {}", agent.getName(), exitCode);
            }

            String result = output.toString().trim();
            log.debug("CLI agent '{}' response length: {} chars", agent.getName(), result.length());
            return result;

        } catch (Exception e) {
            log.error("Error executing CLI agent '{}': {}", agent.getName(), e.getMessage(), e);
            return "Error executing CLI agent: " + e.getMessage();
        }
    }

    /**
     * Build the command for the CLI agent.
     */
    private List<String> buildCommand(AgentProvider agent, String userMessage, String systemMessage) {
        List<String> command = new ArrayList<>();
        command.add(agent.getCommand());

        // Add skip permissions flag if configured
        if (agent.isSkipPermissions() && agent.getSkipPermissionsFlag() != null) {
            command.add(agent.getSkipPermissionsFlag());
        }

        // Add configured args
        if (agent.getArgs() != null) {
            command.addAll(agent.getArgs());
        }

        // Add print flag for non-interactive mode (Claude specific)
        if (agent.getName().equals("claude-cli")) {
            command.add("--print");
        }

        // Build the full prompt with system message if provided
        String fullPrompt;
        if (systemMessage != null && !systemMessage.isEmpty()) {
            fullPrompt = "[System: " + systemMessage + "]\n\n" + userMessage;
        } else {
            fullPrompt = userMessage;
        }

        // Add the prompt as the final argument
        command.add(fullPrompt);

        return command;
    }

    /**
     * Parse Claude's stream-json format to extract text content.
     */
    private String parseClaudeStreamJson(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        try {
            // Simple parsing - look for content in assistant messages
            if (line.contains("\"type\":\"assistant\"") || line.contains("\"type\":\"text\"")) {
                // Extract content field
                int contentStart = line.indexOf("\"content\":\"");
                if (contentStart >= 0) {
                    contentStart += 11; // length of "content":"
                    int contentEnd = line.indexOf("\"", contentStart);
                    if (contentEnd > contentStart) {
                        String content = line.substring(contentStart, contentEnd);
                        // Unescape common JSON escapes
                        content = content.replace("\\n", "\n")
                                .replace("\\t", "\t")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                        return content;
                    }
                }
            }

            // Also check for result/message format
            if (line.contains("\"result\"") && line.contains("\"message\"")) {
                int messageStart = line.indexOf("\"message\":\"");
                if (messageStart >= 0) {
                    messageStart += 11;
                    int messageEnd = line.lastIndexOf("\"");
                    if (messageEnd > messageStart) {
                        return line.substring(messageStart, messageEnd)
                                .replace("\\n", "\n")
                                .replace("\\t", "\t")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                }
            }

        } catch (Exception e) {
            log.trace("Failed to parse stream-json line: {}", line);
        }

        return null;
    }

    // ========================================
    // Inner classes for request/response specs
    // ========================================

    /**
     * Request specification for CLI agent.
     */
    private static class CliAgentRequestSpec implements ChatClientRequestSpec {
        private final CliAgentLLMChat parent;
        private String userMessage;
        private String systemMessage;

        CliAgentRequestSpec(CliAgentLLMChat parent, String userMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
        }

        @Override
        public Builder mutate() {
            return new CliAgentBuilder(parent);
        }

        @Override
        public ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer) {
            // Advisors not supported for CLI agents
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(Advisor... advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(Message... messages) {
            // Extract content from messages
            for (Message msg : messages) {
                if (msg.getText() != null) {
                    if (userMessage == null) {
                        userMessage = msg.getText();
                    } else {
                        userMessage = userMessage + "\n" + msg.getText();
                    }
                }
            }
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(List<Message> messages) {
            return messages(messages.toArray(new Message[0]));
        }

        @Override
        public <T extends ChatOptions> ChatClientRequestSpec options(T options) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) {
            return this;
        }

        @Override
        public ChatClientRequestSpec tools(Object... toolObjects) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(String text) {
            this.systemMessage = text;
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) {
            // Not implemented for CLI agents
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) {
            SimplePromptSystemSpec spec = new SimplePromptSystemSpec();
            consumer.accept(spec);
            this.systemMessage = spec.getText();
            return this;
        }

        @Override
        public ChatClientRequestSpec user(String text) {
            this.userMessage = text;
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text, Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) {
            SimplePromptUserSpec spec = new SimplePromptUserSpec();
            consumer.accept(spec);
            this.userMessage = spec.getText();
            return this;
        }

        @Override
        public ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public CallResponseSpec call() {
            return new CliAgentCallResponseSpec(parent, userMessage, systemMessage);
        }

        @Override
        public StreamResponseSpec stream() {
            return new CliAgentStreamResponseSpec(parent, userMessage, systemMessage);
        }
    }

    /**
     * Call response specification for CLI agent.
     */
    private static class CliAgentCallResponseSpec implements CallResponseSpec {
        private final CliAgentLLMChat parent;
        private final String userMessage;
        private final String systemMessage;
        private String cachedResponse;

        CliAgentCallResponseSpec(CliAgentLLMChat parent, String userMessage, String systemMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
            this.systemMessage = systemMessage;
        }

        private String getResponse() {
            if (cachedResponse == null) {
                cachedResponse = parent.executeAgent(userMessage, systemMessage);
            }
            return cachedResponse;
        }

        @Override
        public <T> T entity(ParameterizedTypeReference<T> type) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public <T> T entity(Class<T> type) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public ChatClientResponse chatClientResponse() {
            ChatResponse response = chatResponse();
            return ChatClientResponse.builder()
                    .chatResponse(response)
                    .build();
        }

        @Override
        public ChatResponse chatResponse() {
            String content = getResponse();
            Generation generation = new Generation(new AssistantMessage(content), null);
            return new ChatResponse(Collections.singletonList(generation));
        }

        @Override
        public String content() {
            return getResponse();
        }
    }

    /**
     * Stream response specification for CLI agent.
     */
    private static class CliAgentStreamResponseSpec implements StreamResponseSpec {
        private final CliAgentLLMChat parent;
        private final String userMessage;
        private final String systemMessage;

        CliAgentStreamResponseSpec(CliAgentLLMChat parent, String userMessage, String systemMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
            this.systemMessage = systemMessage;
        }

        @Override
        public Flux<ChatClientResponse> chatClientResponse() {
            // CLI agents don't truly stream, return single response
            String content = parent.executeAgent(userMessage, systemMessage);
            Generation generation = new Generation(new AssistantMessage(content), null);
            ChatResponse response = new ChatResponse(Collections.singletonList(generation));
            ChatClientResponse clientResponse = ChatClientResponse.builder()
                    .chatResponse(response)
                    .build();
            return Flux.just(clientResponse);
        }

        @Override
        public Flux<ChatResponse> chatResponse() {
            String content = parent.executeAgent(userMessage, systemMessage);
            Generation generation = new Generation(new AssistantMessage(content), null);
            return Flux.just(new ChatResponse(Collections.singletonList(generation)));
        }

        @Override
        public Flux<String> content() {
            String response = parent.executeAgent(userMessage, systemMessage);
            return Flux.just(response);
        }
    }

    /**
     * Builder for CLI agent LLMChat.
     */
    private static class CliAgentBuilder implements Builder {
        private final CliAgentLLMChat parent;

        CliAgentBuilder(CliAgentLLMChat parent) {
            this.parent = parent;
        }

        @Override
        public Builder defaultAdvisors(Advisor... advisors) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(Consumer<AdvisorSpec> advisorSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public Builder defaultOptions(ChatOptions chatOptions) {
            return this;
        }

        @Override
        public Builder defaultUser(String text) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text) {
            return this;
        }

        @Override
        public Builder defaultUser(Consumer<PromptUserSpec> userSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultSystem(String text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Consumer<PromptSystemSpec> systemSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public Builder defaultToolNames(String... toolNames) {
            return this;
        }

        @Override
        public Builder defaultTools(Object... toolObjects) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public Builder defaultToolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public Builder clone() {
            return new CliAgentBuilder(parent);
        }

        @Override
        public LLMChat build() {
            return parent;
        }
    }

    /**
     * Simple implementation of PromptSystemSpec.
     */
    private static class SimplePromptSystemSpec implements PromptSystemSpec {
        private String text;

        String getText() {
            return text;
        }

        @Override
        public PromptSystemSpec text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text, Charset charset) {
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text) {
            return this;
        }

        @Override
        public PromptSystemSpec params(Map<String, Object> p) {
            return this;
        }

        @Override
        public PromptSystemSpec param(String k, Object v) {
            return this;
        }
    }

    /**
     * Simple implementation of PromptUserSpec.
     */
    private static class SimplePromptUserSpec implements PromptUserSpec {
        private String text;

        String getText() {
            return text;
        }

        @Override
        public PromptUserSpec text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text, Charset charset) {
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text) {
            return this;
        }

        @Override
        public PromptUserSpec params(Map<String, Object> p) {
            return this;
        }

        @Override
        public PromptUserSpec param(String k, Object v) {
            return this;
        }

        @Override
        public PromptUserSpec media(Media... media) {
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, URL url) {
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, Resource resource) {
            return this;
        }
    }
}
