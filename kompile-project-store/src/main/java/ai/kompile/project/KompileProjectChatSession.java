/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Summary of a chat session exported to the project's {@code data/chats/} directory.
 * Used by the chat catalog ({@code data/chats/project-chats.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KompileProjectChatSession {
    private String sessionId;
    private String title;
    private String source;
    private String factSheetName;
    private String codeProjectId;
    private int messageCount;
    private String createdAt;
    private String updatedAt;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFactSheetName() { return factSheetName; }
    public void setFactSheetName(String factSheetName) { this.factSheetName = factSheetName; }

    public String getCodeProjectId() { return codeProjectId; }
    public void setCodeProjectId(String codeProjectId) { this.codeProjectId = codeProjectId; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
