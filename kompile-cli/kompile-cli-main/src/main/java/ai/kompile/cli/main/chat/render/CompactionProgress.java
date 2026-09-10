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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.render;

/**
 * Live progress callback for the compaction lifecycle, consumed by
 * {@code AgenticChatLoop#forceCompact(String, CompactionProgress)}.
 * <p>
 * The interface keeps the chat loop free of any terminal/rendering dependency:
 * the production implementation is {@link CompactionProgressIndicator} (an
 * animated transcript block), while tests can pass a recording stub or
 * {@link #NO_OP}. Implementations must tolerate any threading and must never
 * throw back into the compaction path.
 */
public interface CompactionProgress {

    /** Human-readable phase label, e.g. {@code "Summarizing 40 older entries"}. */
    void phase(String label);

    /** Shared no-op instance for call sites without a live UI. */
    CompactionProgress NO_OP = label -> { };
}
