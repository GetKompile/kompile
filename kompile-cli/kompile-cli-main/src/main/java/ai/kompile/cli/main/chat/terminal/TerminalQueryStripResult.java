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

package ai.kompile.cli.main.chat.terminal;

/**
 * Result of filtering one PTY byte chunk. displayBytes are written to the
 * real terminal; queries are sent to the agent-specific decoder so it can
 * synthesize responses back to the subprocess.
 */
public record TerminalQueryStripResult(byte[] displayBytes, String queries) {
    static final TerminalQueryStripResult EMPTY = new TerminalQueryStripResult(new byte[0], "");
}
