# Kompile Chat Memory Abstractions

This module provides chat memory abstractions that interoperate with Spring AI's chat memory system, enabling contextual conversations and memory management for LLM interactions. The system integrates seamlessly with Kompile's LLMChat abstraction for enhanced functionality.

## Overview

The chat memory system consists of several key components:

- **KompileChatMemory**: Core abstraction for managing conversation history
- **KompileChatMemoryRepository**: Storage abstraction for different backends
- **SpringAiChatMemoryAdapter**: Bridge between Kompile and Spring AI memory systems
- **ConversationalLanguageModel**: Enhanced language model with memory capabilities
- **ChatMemoryUtils**: Utilities for working with LLMChat and memory integration

## Key Features

- **Spring AI Compatibility**: Seamless integration with Spring AI's ChatClient and advisors
- **LLMChat Integration**: Full integration with Kompile's LLMChat abstraction
- **Pluggable Storage**: Support for different storage backends (in-memory, database, etc.)
- **Message Window Management**: Automatic handling of conversation length limits
- **Token-aware Memory**: Utilities for managing memory within token limits
- **Multi-conversation Support**: Handle multiple concurrent conversations
- **Intelligent Summarization**: AI-powered conversation summarization

## Quick Start

### Basic Configuration

The system auto-configures with sensible defaults. Add these properties to your `application.yml`:

```yaml
kompile:
  chat:
    memory:
      enabled: true
      max-messages: 20  # Maximum messages to keep in memory window
  llm:
    chat:
      enabled: true
      auto-configure-memory: true
```

### Using ConversationalLanguageModel

```java
@Autowired
private ConversationalLanguageModel languageModel;

public String chat(String conversationId, String userMessage) {
    return languageModel.generateConversationalResponse(
        conversationId, 
        userMessage, 
        List.of("Additional context if needed")
    );
}
```

### Using LLMChat with Memory

```java
@Autowired
private LLMChat llmChat; // Auto-configured with memory if available

public String chatWithMemory(String conversationId, String message) {
    return llmChat.prompt()
        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
        .user(message)
        .call()
        .content();
}
```

### Using ChatMemoryUtils with LLMChat

```java
@Service
public class ConversationService {
    
    @Autowired
    private LLMChat llmChat;
    
    public String simpleConversation(String conversationId, String message) {
        return ChatMemoryUtils.conversationalFlow(llmChat, conversationId, message);
    }
    
    public Flux<String> streamingConversation(String conversationId, String message) {
        return ChatMemoryUtils.streamingConversationalFlow(llmChat, conversationId, message);
    }
}
```

## Architecture

### Core Interfaces

#### KompileChatMemory
The main abstraction for chat memory that extends Spring AI's ChatMemory interface:

```java
public interface KompileChatMemory {
    // Core Spring AI compatibility methods
    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId);
    void clear(String conversationId);
    
    // Extended Kompile-specific methods
    List<Message> get(String conversationId, int lastN);
    int size(String conversationId);
    boolean exists(String conversationId);
    List<String> getActiveConversationIds();
}
```

#### KompileChatMemoryRepository
Storage abstraction for different backends:

```java
public interface KompileChatMemoryRepository {
    void save(String conversationId, List<Message> messages);
    void add(String conversationId, List<Message> messages);
    List<Message> findByConversationId(String conversationId, int limit);
    void deleteByConversationId(String conversationId);
    // ... other storage operations
}
```

### Implementations

#### MessageWindowKompileChatMemory
Window-based memory that keeps only the most recent N messages:

```java
KompileChatMemory memory = MessageWindowKompileChatMemory.builder()
    .repository(repository)
    .maxMessages(10)
    .build();
```

#### InMemoryKompileChatMemoryRepository
In-memory storage suitable for development and testing:

```java
KompileChatMemoryRepository repository = new InMemoryKompileChatMemoryRepository();
```

## Advanced Usage

### Intelligent Memory Management with LLMChat

```java
@Service
public class SmartConversationService {
    
    @Autowired
    private LLMChat llmChat;
    
    @Autowired
    private KompileChatMemory chatMemory;
    
    public String managedConversation(String conversationId, String message, int maxTokens) {
        return ChatMemoryUtils.managedConversationalSession(
            llmChat, 
            chatMemory, 
            conversationId, 
            message, 
            maxTokens
        );
    }
    
    public String conversationWithContext(String conversationId, String message) {
        return ChatMemoryUtils.conversationalFlowWithContext(
            llmChat,
            conversationId,
            message,
            () -> "Additional context from external source"
        );
    }
}
```

### AI-Powered Summarization

```java
@Service
public class ConversationSummarizationService {
    
    @Autowired
    private LLMChat llmChat;
    
    @Autowired
    private KompileChatMemory chatMemory;
    
    public void summarizeIfNeeded(String conversationId, int tokenLimit) {
        List<Message> messages = chatMemory.get(conversationId);
        
        if (ChatMemoryUtils.exceedsTokenLimit(messages, tokenLimit)) {
            // Use AI to create intelligent summary
            String summary = ChatMemoryUtils.buildIntelligentSummary(
                llmChat, 
                messages, 
                tokenLimit / 4
            );
            
            // Replace conversation with summary
            chatMemory.clear(conversationId);
            chatMemory.add(conversationId, 
                ChatMemoryUtils.createAssistantMessage("Summary: " + summary));
        }
    }
}
```

### Batch Processing

```java
@Service
public class BatchConversationService {
    
    @Autowired
    private LLMChat llmChat;
    
    @Autowired
    private KompileChatMemory chatMemory;
    
    public Map<String, String> processBatch(Map<String, String> conversations) {
        return ChatMemoryUtils.batchConversationalProcess(
            llmChat,
            chatMemory,
            conversations,
            4000 // Max tokens per conversation
        );
    }
}
```

### Custom Memory Implementation

```java
@Component
public class CustomChatMemory implements KompileChatMemory {
    
    @Override
    public void add(String conversationId, List<Message> messages) {
        // Custom storage logic
    }
    
    @Override
    public List<Message> get(String conversationId) {
        // Custom retrieval logic
    }
    
    // ... implement other methods
}
```

## ChatMemoryUtils API

### Conversation Management

```java
// Generate conversation IDs
String conversationId = ChatMemoryUtils.generateConversationId();
String userConversationId = ChatMemoryUtils.generateUserConversationId("user123");
String sessionConversationId = ChatMemoryUtils.generateSessionConversationId("session456");

// Simple conversational flow
String response = ChatMemoryUtils.conversationalFlow(llmChat, conversationId, "Hello!");

// Streaming conversation
Flux<String> stream = ChatMemoryUtils.streamingConversationalFlow(llmChat, conversationId, "Tell me a story");

// With context
String contextualResponse = ChatMemoryUtils.conversationalFlowWithContext(
    llmChat, 
    conversationId, 
    "What should I do?",
    () -> "User preferences: outdoor activities"
);
```

### Memory Management

```java
// Check token usage
List<Message> messages = chatMemory.get(conversationId);
boolean exceedsLimit = ChatMemoryUtils.exceedsTokenLimit(messages, 4000);

// Create summaries
String simpleSummary = ChatMemoryUtils.buildConversationSummary(messages, 500);
String aiSummary = ChatMemoryUtils.buildIntelligentSummary(llmChat, messages, 500);

// Automatic memory management
boolean summarized = ChatMemoryUtils.manageConversationMemory(
    chatMemory, llmChat, conversationId, 4000
);
```

### Message Creation

```java
// Create messages
Message userMsg = ChatMemoryUtils.createUserMessage("Hello");
Message assistantMsg = ChatMemoryUtils.createAssistantMessage("Hi there!");

// Validate conversation IDs
boolean isValid = ChatMemoryUtils.isValidConversationId(conversationId);
String sanitized = ChatMemoryUtils.sanitizeConversationId(rawId);
```

## Configuration Options

### Application Properties

```yaml
kompile:
  chat:
    memory:
      enabled: true          # Enable/disable chat memory
      max-messages: 20       # Maximum messages in window
  llm:
    chat:
      enabled: true
      auto-configure-memory: true
      default-system: "You are a helpful assistant with conversation history"
```

### Custom Bean Configuration

```java
@Configuration
public class ChatMemoryConfig {
    
    @Bean
    @Primary
    public KompileChatMemoryRepository customRepository() {
        return new DatabaseChatMemoryRepository();
    }
    
    @Bean
    public KompileChatMemory customChatMemory(KompileChatMemoryRepository repository) {
        return MessageWindowKompileChatMemory.builder()
            .repository(repository)
            .maxMessages(50)
            .build();
    }
    
    @Bean
    public LLMChat customMemoryLLMChat(
            ChatClient.Builder builder, 
            ChatMemory chatMemory) {
        return LLMChatUtils.createWithMessageMemory(builder, chatMemory);
    }
}
```

## Integration Examples

### REST Controller with Memory

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    
    @Autowired
    private LLMChat llmChat;
    
    @PostMapping("/conversation/{conversationId}")
    public String chat(
            @PathVariable String conversationId,
            @RequestBody String message) {
        
        return ChatMemoryUtils.conversationalFlow(llmChat, conversationId, message);
    }
    
    @PostMapping("/stream/{conversationId}")
    public Flux<String> streamChat(
            @PathVariable String conversationId,
            @RequestBody String message) {
        
        return ChatMemoryUtils.streamingConversationalFlow(llmChat, conversationId, message);
    }
    
    @PostMapping("/managed/{conversationId}")
    public String managedChat(
            @PathVariable String conversationId,
            @RequestBody String message,
            @RequestParam(defaultValue = "4000") int maxTokens) {
        
        return ChatMemoryUtils.managedConversationalSession(
            llmChat, chatMemory, conversationId, message, maxTokens
        );
    }
}
```

## Best Practices

1. **Use LLMChat Integration**: Leverage ChatMemoryUtils methods that work directly with LLMChat
2. **Conversation ID Management**: Use structured conversation IDs with appropriate generators
3. **Memory Limits**: Implement automatic summarization for long conversations
4. **Token Awareness**: Monitor token usage and implement intelligent memory management
5. **Error Handling**: Handle memory operation failures gracefully
6. **Cleanup**: Implement conversation cleanup for inactive sessions
7. **Streaming**: Use streaming for real-time user interactions

## Testing

### Unit Testing Memory Components

```java
@Test
public void testConversationalFlow() {
    // Mock LLMChat
    LLMChat mockLLMChat = mock(LLMChat.class);
    when(mockLLMChat.prompt()).thenReturn(mockPromptSpec);
    when(mockPromptSpec.advisors(any())).thenReturn(mockPromptSpec);
    when(mockPromptSpec.user("Hello")).thenReturn(mockPromptSpec);
    when(mockPromptSpec.call()).thenReturn(mockCallSpec);
    when(mockCallSpec.content()).thenReturn("Hi there!");
    
    String response = ChatMemoryUtils.conversationalFlow(
        mockLLMChat, "test-conversation", "Hello"
    );
    
    assertThat(response).isEqualTo("Hi there!");
}
```

## Troubleshooting

### Common Issues

1. **Memory Not Working**: Ensure ChatMemory bean is configured and LLMChat has memory advisor
2. **Token Limits Exceeded**: Use ChatMemoryUtils.managedConversationalSession for automatic management
3. **Conversation ID Conflicts**: Use ChatMemoryUtils ID generators
4. **Performance Issues**: Consider streaming for large responses

### Debug Logging

```yaml
logging:
  level:
    ai.kompile.core.llm.memory: DEBUG
    ai.kompile.core.llm.chat: DEBUG
```

## Dependencies

This module requires:
- Spring AI (Chat Memory support)
- Spring Boot (Auto-configuration)
- Spring Framework (Core)
- Project Reactor (for streaming)
- Kompile LLMChat (for enhanced functionality)

## License

Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0.
