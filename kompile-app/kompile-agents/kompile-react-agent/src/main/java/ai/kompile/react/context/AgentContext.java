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
package ai.kompile.react.context;

import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.TokenUsage;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the runtime state and resources for a ReAct agent's execution.
 * This class holds references to the agent's memory, toolkit, and execution state.
 *
 * <p>Implementation based on LoongFlow's AgentContext pattern.
 */
@Getter
public class AgentContext {

    /**
     * Unique identifier for this execution context.
     */
    private final String executionId;

    /**
     * The agent's memory for storing messages.
     */
    private final Memory memory;

    /**
     * The toolkit containing available tools.
     */
    private final Toolkit toolkit;

    /**
     * Maximum number of reasoning steps allowed.
     */
    private final int maxSteps;

    /**
     * Current step number (0-indexed).
     */
    private final AtomicInteger currentStep;

    /**
     * Accumulated token usage across all steps.
     */
    @Setter
    private TokenUsage totalUsage;

    /**
     * Custom metadata for this execution.
     */
    private final Map<String, Object> metadata;

    /**
     * Whether the execution has been cancelled.
     */
    @Setter
    private volatile boolean cancelled;

    /**
     * The system prompt for this agent.
     */
    @Getter
    @Setter
    private String systemPrompt;

    @Builder
    public AgentContext(
            String executionId,
            Memory memory,
            Toolkit toolkit,
            int maxSteps,
            String systemPrompt,
            Map<String, Object> metadata
    ) {
        this.executionId = executionId != null ? executionId : UUID.randomUUID().toString();
        this.memory = memory;
        this.toolkit = toolkit;
        this.maxSteps = maxSteps > 0 ? maxSteps : 10;
        this.systemPrompt = systemPrompt;
        this.currentStep = new AtomicInteger(0);
        this.totalUsage = TokenUsage.empty();
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.cancelled = false;
    }

    /**
     * Add a message to the agent's memory.
     *
     * @param message The message to add
     */
    public void addMessage(ReActMessage message) {
        if (message != null) {
            memory.add(message);
        }
    }

    /**
     * Add multiple messages to the agent's memory.
     *
     * @param messages The messages to add
     */
    public void addMessages(List<ReActMessage> messages) {
        if (messages != null) {
            memory.addAll(messages);
        }
    }

    /**
     * Remove a message from the agent's memory.
     *
     * @param messageId The message ID
     * @return true if the message was removed
     */
    public boolean removeMessage(UUID messageId) {
        return memory.remove(messageId);
    }

    /**
     * Get all messages from memory.
     *
     * @return The list of messages
     */
    public List<ReActMessage> getMessages() {
        return memory.getMessages();
    }

    /**
     * Increment and get the current step.
     *
     * @return The new step number
     */
    public int incrementStep() {
        return currentStep.incrementAndGet();
    }

    /**
     * Get the current step number.
     *
     * @return The current step
     */
    public int getCurrentStep() {
        return currentStep.get();
    }

    /**
     * Check if we've reached the maximum number of steps.
     *
     * @return true if max steps reached
     */
    public boolean hasReachedMaxSteps() {
        return currentStep.get() >= maxSteps;
    }

    /**
     * Add token usage to the total.
     *
     * @param usage The usage to add
     */
    public void addUsage(TokenUsage usage) {
        if (usage != null) {
            this.totalUsage = this.totalUsage.add(usage);
        }
    }

    /**
     * Get metadata value.
     *
     * @param key The metadata key
     * @return The value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * Set metadata value.
     *
     * @param key The metadata key
     * @param value The value
     */
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * Check if the execution should stop.
     *
     * @return true if execution should stop
     */
    public boolean shouldStop() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    /**
     * Create a new context builder with the same toolkit and settings.
     *
     * @return A new builder
     */
    public AgentContextBuilder toBuilder() {
        return AgentContext.builder()
                .memory(memory)
                .toolkit(toolkit)
                .maxSteps(maxSteps)
                .systemPrompt(systemPrompt)
                .metadata(new HashMap<>(metadata));
    }
}
