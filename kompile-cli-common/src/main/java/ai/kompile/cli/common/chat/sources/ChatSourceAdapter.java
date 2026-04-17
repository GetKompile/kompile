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

package ai.kompile.cli.common.chat.sources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Adapter for reading chat/transcript data from a single external agent CLI.
 * Implementations are discovered via {@link java.util.ServiceLoader}; the unified
 * identifier string (e.g. {@code claude-code}, {@code cursor}) is used everywhere
 * the source needs to be named in APIs, logs, and storage metadata.
 */
public interface ChatSourceAdapter {

    String id();

    String displayName();

    SourceInfo discover();

    List<ChatSessionSummary> list() throws IOException;

    List<ChatTurn> readTurns(String sessionId) throws IOException;

    default Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        return Optional.empty();
    }

    default String resolveTitle(String sessionId) throws IOException {
        return sessionId;
    }
}
