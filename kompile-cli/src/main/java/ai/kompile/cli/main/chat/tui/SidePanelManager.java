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

package ai.kompile.cli.main.chat.tui;

/**
 * Lightweight side-panel state holder used by MCP tools when a full TUI is not
 * attached. Interactive frontends can replace this with richer rendering later.
 */
public class SidePanelManager {
    private volatile boolean visible;
    private volatile String title = "Side Panel";
    private volatile String content = "";
    private volatile long version;

    public boolean isVisible() {
        return visible;
    }

    public synchronized void show(String title, String content) {
        this.visible = true;
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        this.content = content != null ? content : "";
        this.version++;
    }

    public synchronized void hide() {
        this.visible = false;
        this.version++;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public long getVersion() {
        return version;
    }

    public Snapshot snapshot() {
        return new Snapshot(visible, title, content, version);
    }

    public record Snapshot(boolean visible, String title, String content, long version) {}
}
