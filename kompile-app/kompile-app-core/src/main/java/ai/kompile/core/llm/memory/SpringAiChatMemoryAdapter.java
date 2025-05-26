/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.core.llm.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Adapter that bridges Kompile's chat memory abstraction with Spring AI's ChatMemory interface.
 * This allows Kompile's chat memory implementations to be used seamlessly with Spring AI's
 * ChatClient and advisors.
 * 
 * <p>This adapter implements the Spring AI ChatMemory interface while delegating
 * the actual storage operations to a KompileChatMemory implementation.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public class SpringAiChatMemoryAdapter implements ChatMemory {

    private final KompileChatMemory kompileChatMemory;

    /**
     * Creates a new adapter for the given Kompile chat memory implementation.
     * 
     * @param kompileChatMemory the Kompile chat memory implementation to adapt
     */
    public SpringAiChatMemoryAdapter(KompileChatMemory kompileChatMemory) {
        Assert.notNull(kompileChatMemory, "kompileChatMemory cannot be null");
        this.kompileChatMemory = kompileChatMemory;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        kompileChatMemory.add(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return kompileChatMemory.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        kompileChatMemory.clear(conversationId);
    }

    /**
     * Gets the underlying Kompile chat memory implementation.
     * 
     * @return the Kompile chat memory implementation
     */
    public KompileChatMemory getKompileChatMemory() {
        return kompileChatMemory;
    }
}
