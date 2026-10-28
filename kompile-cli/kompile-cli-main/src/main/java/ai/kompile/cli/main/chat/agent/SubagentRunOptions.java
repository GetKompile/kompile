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

package ai.kompile.cli.main.chat.agent;

/**
 * Opt-in knobs for one subagent delegation. Default instance is non-resumable:
 * callers that never set these get byte-for-byte today's behavior — no sidecar,
 * no ledger, no registry entry.
 *
 * @param resumable persist a portable ledger + sidecar so the run can be resumed later
 * @param name      optional unique slug; non-blank implies resumable
 */
public record SubagentRunOptions(boolean resumable, String name) {

    public static final SubagentRunOptions DEFAULT = new SubagentRunOptions(false, null);

    public static SubagentRunOptions resumable(String name) {
        return new SubagentRunOptions(true, (name == null || name.isBlank()) ? null : name.strip());
    }

    /** A name implies resumability. */
    public boolean effectiveResumable() {
        return resumable || (name != null && !name.isBlank());
    }
}
