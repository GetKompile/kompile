# Kompile LLMChat Abstraction

This module provides a comprehensive abstraction layer over Spring AI's ChatClient, offering enhanced functionality while maintaining full interoperability with the Spring AI ecosystem. The LLMChat interface simplifies LLM interactions and provides additional features like enhanced error handling, conversation management, and streamlined usage patterns.

## Overview

The LLMChat abstraction consists of several key components:

- **LLMChat**: Core interface providing a fluent API for LLM interactions
- **DefaultLLMChat**: Default implementation wrapping Spring AI's ChatClient
- **LLMChatFactory**: Factory for creating LLMChat instances with various configurations
- **LLMChatUtils**: Utility methods for common use cases (RAG, memory, etc.)
- **LLMChatConfiguration**: Spring Boot auto-configuration

## Key Features

- **Full Spring AI Compatibility**: Complete interoperability with Spring AI's ChatClient
- **Fluent API**: Intuitive, builder-pattern interface similar to WebClient/RestClient
- **Enhanced Error Handling**: Robust error management and graceful degradation
- **Auto-Configuration**: Spring Boot integration with sensible defaults
- **Streaming Support**: Both synchronous and reactive streaming capabilities
- **Advisor Integration**: Seamless integration with Spring AI advisors
- **Function Calling**: Built-in support for LLM function calling
- **Memory & RAG**: Utilities for chat memory and retrieval-augmented generation

## Quick Start

### Basic Usage

```java
@Autowired
private LLMChat llmChat;

public String simpleChat(String userMessage) {
    return llmChat.prompt()
        .user(userMessage)
        .call()
        .content();
}
```

### With System Message

```java
public String chatWithPersona(String userMessage) {
    return llmChat.prompt()
        .system("You are a helpful coding assistant.")
        .user(userMessage)
        .call()
        .content();
}
```

### Streaming Response

```java
public Flux<String> streamingChat(String userMessage) {
    return llmChat.prompt()
        .user(userMessage)
        .stream()
        .content();
}
```

## Configuration

### Application Properties

```yaml
kompile:
  llm:
    chat:
      enabled: true
      default-system: "You are a helpful AI assistant"
      auto-configure-memory: true
      auto-configure-rag: true
      rag-max-documents: 5
```

### Spring Configuration

```java
@Configuration
public class LLMChatConfig {
    
    @Bean
    public LLMChat customLLMChat(ChatClient.Builder builder) {
        return LLMChatFactory.builder(builder)
            .defaultSystem("You are a specialized assistant")
            .build();
    }
}
```

## Advanced Usage

### With Chat Memory

```java
@Service
public class ConversationalService {
    
    private final LLMChat llmChat;
    
    public ConversationalService(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.llmChat = LLMChatUtils.createWithMessageMemory(builder, chatMemory);
    }
    
    public String chat(String conversationId, String message) {
        return llmChat.prompt()
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
            .user(message)
            .call()
            .content();
    }
}
```

### With RAG (Retrieval-Augmented Generation)

```java
@Service
public class DocumentQAService {
    
    private final LLMChat llmChat;
    
    public DocumentQAService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.llmChat = LLMChatUtils.createWithRAG(builder, vectorStore);
    }
    
    public String askQuestion(String question) {
        return llmChat.prompt()
            .user(question)
            .call()
            .content();
    }
}
```

### Comprehensive Setup (Memory + RAG)

```java
@Bean
public LLMChat comprehensiveLLMChat(
        ChatClient.Builder builder,
        ChatMemory chatMemory,
        VectorStore vectorStore) {
    
    return LLMChatUtils.createWithMemoryAndRAG(builder, chatMemory, vectorStore);
}
```

### Function Calling

```java
@Service
public class FunctionCallingService {
    
    private final LLMChat llmChat;
    
    public FunctionCallingService(ChatClient.Builder builder) {
        this.llmChat = LLMChatFactory.createWithFunctions(
            builder, 
            "getCurrentWeather", 
            "sendEmail"
        );
    }
    
    public ChatResponse chatWithFunctions(String message) {
        return llmChat.prompt()
            .user(message)
            .functions("getCurrentWeather", "sendEmail")
            .call()
            .chatResponse();
    }
}
```

### Entity Mapping

```java
public record BookRecommendation(String title, String author, String genre) {}

public List<BookRecommendation> getBookRecommendations(String preferences) {
    return llmChat.prompt()
        .system("You are a book recommendation assistant. Return recommendations as JSON.")
        .user("Recommend books based on: " + preferences)
        .call()
        .entity(new ParameterizedTypeReference<List<BookRecommendation>>() {});
}
```

## Factory Methods

### LLMChatFactory

```java
// Basic creation
LLMChat chat = LLMChatFactory.create(chatClientBuilder);

// With system message
LLMChat chat = LLMChatFactory.createWithSystem(
    chatClientBuilder, 
    "You are a helpful assistant"
);

// With advisors
LLMChat chat = LLMChatFactory.createWithAdvisors(
    chatClientBuilder, 
    memoryAdvisor, 
    ragAdvisor
);

// Fully configured
LLMChat chat = LLMChatFactory.createConfigured(
    chatClientBuilder,
    systemMessage,
    advisors,
    functionNames,
    options
);
```

### LLMChatUtils

```java
// Chat with memory
LLMChat memoryChat = LLMChatUtils.createWithMessageMemory(builder, chatMemory);

// Document analysis with RAG
LLMChat docAnalyst = LLMChatUtils.createDocumentAnalyst(builder, vectorStore, 10);

// Conversational bot with persona
LLMChat bot = LLMChatUtils.createConversationalBot(
    builder, 
    chatMemory, 
    "You are a friendly coding mentor"
);

// Comprehensive setup
LLMChat comprehensive = LLMChatUtils.createComprehensive(
    builder,
    chatMemory,
    vectorStore,
    searchRequest,
    customAdvisor1,
    customAdvisor2
);
```

## API Reference

### Core Interfaces

#### LLMChat
```java
public interface LLMChat {
    PromptSpec prompt();
    RequestSpec prompt(Prompt prompt);
    RequestSpec prompt(String content);
    static Builder builder();
}
```

#### PromptSpec
```java
public interface PromptSpec {
    PromptSpec user(String content);
    PromptSpec user(Consumer<UserSpec> userSpec);
    PromptSpec system(String content);
    PromptSpec system(Consumer<SystemSpec> systemSpec);
    PromptSpec advisors(Consumer<AdvisorSpec> advisorSpec);
    PromptSpec advisors(Advisor... advisors);
    PromptSpec functions(String... functionNames);
    PromptSpec options(Map<String, Object> options);
    CallSpec call();
    StreamSpec stream();
}
```

#### CallSpec
```java
public interface CallSpec {
    String content();
    ChatResponse chatResponse();
    <T> T entity(Class<T> entityClass);
    <T> T entity(ParameterizedTypeReference<T> entityType);
}
```

#### StreamSpec
```java
public interface StreamSpec {
    Flux<String> content();
    Flux<ChatResponse> chatResponse();
}
```

## Integration Examples

### REST Controller

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    
    private final LLMChat llmChat;
    
    public ChatController(LLMChat llmChat) {
        this.llmChat = llmChat;
    }
    
    @PostMapping("/simple")
    public String simpleChat(@RequestBody String message) {
        return llmChat.prompt()
            .user(message)
            .call()
            .content();
    }
    
    @PostMapping("/stream")
    public Flux<String> streamChat(@RequestBody String message) {
        return llmChat.prompt()
            .user(message)
            .stream()
            .content();
    }
    
    @PostMapping("/conversation/{conversationId}")
    public String conversationalChat(
            @PathVariable String conversationId,
            @RequestBody String message) {
        
        return llmChat.prompt()
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
            .user(message)
            .call()
            .content();
    }
}
```

### WebSocket Integration

```java
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private final LLMChat llmChat;
    
    public ChatWebSocketHandler(LLMChat llmChat) {
        this.llmChat = llmChat;
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        llmChat.prompt()
            .user(message.getPayload())
            .stream()
            .content()
            .subscribe(response -> {
                try {
                    session.sendMessage(new TextMessage(response));
                } catch (IOException e) {
                    // Handle error
                }
            });
    }
}
```

## Error Handling

### Custom Error Handling

```java
public String safeChat(String message) {
    try {
        return llmChat.prompt()
            .user(message)
            .call()
            .content();
    } catch (Exception e) {
        logger.error("Chat error: {}", e.getMessage(), e);
        return "I'm sorry, I'm having trouble responding right now. Please try again.";
    }
}
```

### Reactive Error Handling

```java
public Flux<String> resilientStreamChat(String message) {
    return llmChat.prompt()
        .user(message)
        .stream()
        .content()
        .onErrorResume(error -> {
            logger.error("Streaming error: {}", error.getMessage(), error);
            return Flux.just("Error: Unable to process your request.");
        })
        .timeout(Duration.ofSeconds(30))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
}
```

## Testing

### Unit Testing

```java
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
    
    @Mock
    private LLMChat llmChat;
    
    @Mock
    private LLMChat.PromptSpec promptSpec;
    
    @Mock
    private LLMChat.CallSpec callSpec;
    
    @Test
    void shouldProcessUserMessage() {
        // Given
        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Test response");
        
        // When
        String result = chatService.processMessage("Hello");
        
        // Then
        assertThat(result).isEqualTo("Test response");
    }
}
```

### Integration Testing

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.openai.api-key=test-key",
    "kompile.llm.chat.enabled=true"
})
class LLMChatIntegrationTest {
    
    @Autowired
    private LLMChat llmChat;
    
    @Test
    void shouldInitializeLLMChat() {
        assertThat(llmChat).isNotNull();
    }
    
    @Test
    void shouldHandleBasicPrompt() {
        String response = llmChat.prompt()
            .user("Hello")
            .call()
            .content();
        
        assertThat(response).isNotBlank();
    }
}
```

## Best Practices

### 1. Use Appropriate Abstractions
- Use `LLMChat` for enhanced functionality and Kompile-specific features
- Use `ChatClient` directly when you need maximum control
- Use `LLMChatUtils` for common patterns (memory, RAG, etc.)

### 2. Error Handling
- Always implement proper error handling for LLM interactions
- Use reactive error handling for streaming operations
- Implement timeouts and retry logic for resilience

### 3. Resource Management
- Configure appropriate timeouts for long-running operations
- Use streaming for large responses to improve user experience
- Implement proper cleanup for conversation resources

### 4. Security
- Validate and sanitize user inputs
- Implement rate limiting for API calls
- Use secure storage for API keys and sensitive data

### 5. Performance
- Use streaming for real-time user interactions
- Implement caching where appropriate
- Monitor token usage and costs

## Migration from ChatClient

Migrating from Spring AI's ChatClient to LLMChat is straightforward:

### Before (ChatClient)
```java
String response = chatClient.prompt()
    .user(userMessage)
    .call()
    .content();
```

### After (LLMChat)
```java
String response = llmChat.prompt()
    .user(userMessage)
    .call()
    .content();
```

The API is nearly identical, with LLMChat providing additional functionality while maintaining full compatibility.

## Troubleshooting

### Common Issues

1. **LLMChat not auto-configured**: Ensure `ChatClient.Builder` bean is available and properties are correctly set
2. **Memory not working**: Check that `ChatMemory` bean is configured and auto-configuration is enabled
3. **RAG not retrieving documents**: Verify `VectorStore` configuration and document indexing
4. **Streaming not working**: Ensure reactive dependencies are on the classpath

### Debug Logging

```yaml
logging:
  level:
    ai.kompile.core.llm.chat: DEBUG
    org.springframework.ai: DEBUG
```

## Dependencies

This module requires:
- Spring AI (ChatClient support)
- Spring Boot (Auto-configuration)
- Spring WebFlux (for streaming support)
- Project Reactor (for reactive operations)

## License

Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0.
