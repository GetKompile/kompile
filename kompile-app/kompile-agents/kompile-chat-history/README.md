# Kompile Chat History Module

## Overview

The `kompile-chat-history` module provides persistent chat history functionality for the Kompile RAG application. It stores chat sessions and messages in a database, supporting both embedded H2 and external JDBC databases.

## Features

- **Embedded H2 Database**: Default configuration with file-based storage
- **Overridable JDBC Support**: Can use PostgreSQL, MySQL, or other JDBC databases
- **Session Management**: Create, list, update, and delete chat sessions
- **Message Storage**: Store user and assistant messages with timestamps
- **REST API**: Full CRUD operations via REST endpoints
- **Frontend Integration**: Chat history sidebar with session management

## Architecture

### Backend Components

**Domain Entities:**
- `ChatSession`: Represents a conversation session
- `ChatMessage`: Individual messages within a session

**Repositories:**
- `ChatSessionRepository`: JPA repository for sessions
- `ChatMessageRepository`: JPA repository for messages

**Services:**
- `ChatHistoryService`: Business logic for chat history operations

**Controllers:**
- `ChatHistoryController`: REST API endpoints at `/api/chat-history`

**Configuration:**
- `ChatHistoryDataSourceConfig`: Database configuration
- `ChatHistoryProperties`: Configuration properties

### Frontend Components

**Services:**
- `ChatHistoryService`: Angular service for API calls

**Components:**
- Enhanced `ChatInterfaceComponent` with history sidebar
- Session list with create/load/delete operations

## Configuration

### Default Configuration (Embedded H2)

```properties
# Enable chat history (default: true)
kompile.chat.history.enabled=true

# Database type: h2, postgres, mysql
kompile.chat.history.database-type=h2

# H2 database file path
kompile.chat.history.h2-database-path=./data/chat-history

# Enable H2 console for debugging
kompile.chat.history.h2-console-enabled=false
```

### PostgreSQL Configuration

```properties
kompile.chat.history.enabled=true
kompile.chat.history.database-type=postgres
kompile.chat.history.jdbc-url=jdbc:postgresql://localhost:5432/kompile_chat
kompile.chat.history.jdbc-username=kompile
kompile.chat.history.jdbc-password=password
kompile.chat.history.jdbc-driver-class-name=org.postgresql.Driver
```

### MySQL Configuration

```properties
kompile.chat.history.enabled=true
kompile.chat.history.database-type=mysql
kompile.chat.history.jdbc-url=jdbc:mysql://localhost:3306/kompile_chat
kompile.chat.history.jdbc-username=kompile
kompile.chat.history.jdbc-password=password
kompile.chat.history.jdbc-driver-class-name=com.mysql.cj.jdbc.Driver
```

## REST API Endpoints

### Sessions

- **POST** `/api/chat-history/sessions` - Create a new session
  ```json
  {
    "title": "My Chat",
    "userId": "user123"
  }
  ```

- **GET** `/api/chat-history/sessions` - Get all sessions (optionally filter by userId)

- **GET** `/api/chat-history/sessions/{sessionId}` - Get session with messages

- **PATCH** `/api/chat-history/sessions/{sessionId}/title` - Update session title
  ```json
  {
    "title": "Updated Title"
  }
  ```

- **DELETE** `/api/chat-history/sessions/{sessionId}` - Delete a session

### Messages

- **POST** `/api/chat-history/sessions/{sessionId}/messages` - Add a message
  ```json
  {
    "role": "USER",
    "content": "Hello, assistant!",
    "model": "gpt-4"
  }
  ```

- **GET** `/api/chat-history/sessions/{sessionId}/messages` - Get all messages for a session

## Usage in RAG Application

The chat history is automatically integrated into the main Kompile RAG application when the `kompile-chat-history` dependency is included.

### Frontend Usage

1. **Toggle History Sidebar**: Click the hamburger menu (☰) button
2. **New Chat**: Click "+ New Chat" button to start a fresh conversation
3. **Load Session**: Click on a session in the list to load it
4. **Delete Session**: Hover over a session and click the × button

### Programmatic Usage

```java
@Autowired
private ChatHistoryService chatHistoryService;

// Create a session
ChatSession session = chatHistoryService.createSession("My Chat", "user123");

// Add a user message
chatHistoryService.addMessage(session.getSessionId(),
    ChatMessage.MessageRole.USER,
    "What is RAG?",
    null);

// Add an assistant response
chatHistoryService.addMessage(session.getSessionId(),
    ChatMessage.MessageRole.ASSISTANT,
    "RAG stands for Retrieval-Augmented Generation...",
    "gpt-4");

// Load all sessions for a user
List<ChatSession> sessions = chatHistoryService.getUserSessions("user123");

// Load a specific session
Optional<ChatSession> loadedSession = chatHistoryService.getSession(sessionId);
```

## Database Schema

### chat_sessions

| Column      | Type         | Description                    |
|-------------|--------------|--------------------------------|
| id          | BIGINT       | Primary key                    |
| session_id  | VARCHAR(255) | Unique session identifier      |
| title       | VARCHAR(255) | Session title                  |
| description | TEXT         | Optional description           |
| created_at  | TIMESTAMP    | Creation timestamp             |
| updated_at  | TIMESTAMP    | Last update timestamp          |
| user_id     | VARCHAR(255) | User identifier                |
| metadata    | TEXT         | Optional JSON metadata         |

### chat_messages

| Column      | Type         | Description                       |
|-------------|--------------|-----------------------------------|
| id          | BIGINT       | Primary key                       |
| session_id  | BIGINT       | Foreign key to chat_sessions      |
| role        | VARCHAR(50)  | USER, ASSISTANT, SYSTEM, or TOOL  |
| content     | TEXT         | Message content                   |
| created_at  | TIMESTAMP    | Creation timestamp                |
| model       | VARCHAR(255) | Model used (optional)             |
| token_count | INTEGER      | Token count (optional)            |
| metadata    | TEXT         | Optional JSON metadata            |

## Building as a Dynamic Module

The chat history module follows the Kompile pattern for dynamic module assembly:

1. **Add to parent POM modules:**
   ```xml
   <module>kompile-chat-history</module>
   ```

2. **Add to dependency management:**
   ```xml
   <dependency>
     <groupId>ai.kompile</groupId>
     <artifactId>kompile-chat-history</artifactId>
     <version>${project.version}</version>
   </dependency>
   ```

3. **Include in RAG application:**
   ```xml
   <dependency>
     <groupId>ai.kompile</groupId>
     <artifactId>kompile-chat-history</artifactId>
   </dependency>
   ```

4. **Enable/disable via configuration:**
   ```properties
   kompile.chat.history.enabled=true
   ```

## Dependencies

- Spring Boot Starter Data JPA
- Spring Boot Starter Web
- H2 Database (embedded)
- Jackson (JSON serialization)
- Lombok

To use external databases, add the appropriate JDBC driver:

**PostgreSQL:**
```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

**MySQL:**
```xml
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
</dependency>
```

## Development

### Build

```bash
cd kompile-app/kompile-chat-history
mvn clean install
```

### Run with Main Application

```bash
cd kompile-app/kompile-app-main
mvn spring-boot:run
```

### Access H2 Console (if enabled)

When `kompile.chat.history.h2-console-enabled=true`:

1. Navigate to: `http://localhost:8080/h2-console`
2. JDBC URL: `jdbc:h2:file:./data/chat-history`
3. Username: `sa`
4. Password: (leave empty)

## License

Copyright 2025 Kompile Inc.

Licensed under the Apache License, Version 2.0.
