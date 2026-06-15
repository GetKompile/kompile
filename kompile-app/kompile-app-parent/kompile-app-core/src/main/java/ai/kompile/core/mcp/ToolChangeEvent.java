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

package ai.kompile.core.mcp;

import org.springframework.context.ApplicationEvent;

/**
 * Event published when tool definitions change.
 * Components can listen for this event to update their tool caches.
 */
public class ToolChangeEvent extends ApplicationEvent {

    public enum ChangeType {
        CREATED,
        UPDATED,
        DELETED,
        ENABLED,
        DISABLED,
        REFRESHED
    }

    private final String toolName;
    private final ChangeType changeType;
    private final EnhancedToolDefinition toolDefinition;

    public ToolChangeEvent(Object source, String toolName, ChangeType changeType, EnhancedToolDefinition toolDefinition) {
        super(source);
        this.toolName = toolName;
        this.changeType = changeType;
        this.toolDefinition = toolDefinition;
    }

    public String getToolName() {
        return toolName;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public EnhancedToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String toString() {
        return "ToolChangeEvent{" +
                "toolName='" + toolName + '\'' +
                ", changeType=" + changeType +
                '}';
    }
}
