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

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.main.chat.ChatHistory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unified reader that loads conversation turns from kompile sessions or
 * directly from external agent storage. All source-specific parsing is
 * delegated to {@link ChatSourceAdapter} instances discovered via
 * {@link ChatSourceRegistry}.
 */
public class ConversationReader {

    public static List<ChatHistory.Turn> readKompileSession(String sessionId) throws IOException {
        if (!ChatHistory.exists(sessionId)) {
            throw new IOException("Kompile session not found: " + sessionId);
        }
        return new ChatHistory(sessionId).readTurns();
    }

    public static List<ChatHistory.Turn> readExternalSession(String source, String externalId) throws IOException {
        ChatSourceAdapter adapter = requireAdapter(source);
        List<ChatTurn> turns = adapter.readTurns(externalId);
        List<ChatHistory.Turn> out = new ArrayList<>(turns.size());
        for (ChatTurn t : turns) {
            out.add(new ChatHistory.Turn(t.role(), t.content(), t.rawContentBlocks()));
        }
        return out;
    }

    public static Path resolveExternalWorkingDirectory(String source, String externalId) throws IOException {
        ChatSourceAdapter adapter = requireAdapter(source);
        Optional<Path> cwd = adapter.resolveWorkingDirectory(externalId);
        if (cwd.isEmpty()) {
            throw new IOException("No working directory recorded for " + source + " session: " + externalId);
        }
        return cwd.get();
    }

    private static ChatSourceAdapter requireAdapter(String source) throws IOException {
        String normalized = normalizeSourceId(source);
        Optional<ChatSourceAdapter> adapter = ChatSourceRegistry.getInstance().find(normalized);
        if (adapter.isEmpty()) {
            throw new FileNotFoundException("Unknown source: " + source
                    + " (known: " + ChatSourceRegistry.getInstance().ids() + ")");
        }
        return adapter.get();
    }

    private static String normalizeSourceId(String source) {
        if (source == null) return "";
        String lower = source.toLowerCase();
        return "claude".equals(lower) ? "claude-code" : lower;
    }
}
