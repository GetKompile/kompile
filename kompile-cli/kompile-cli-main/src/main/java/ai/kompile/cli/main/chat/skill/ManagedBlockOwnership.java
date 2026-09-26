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

package ai.kompile.cli.main.chat.skill;

/**
 * Shared owner-tagging and orphan-reclaim logic for KOMPILE-managed blocks that CLI
 * sessions append to project instruction files (AGENTS.md, GEMINI.md). A managed
 * block's BEGIN line carries an owner token naming the process that must remove it
 * on cleanup; a session that dies without cleaning up (crash, SIGKILL) leaves its
 * block behind forever. The next install reclaims blocks whose owner is no longer
 * alive, plus legacy blocks with no owner token, before appending a new block.
 *
 * <p>Used by {@code SystemPromptManager} (system-prompt blocks) and
 * {@code SkillsInjection} (skills blocks) so this logic is defined exactly once.
 */
public final class ManagedBlockOwnership {

    /** Tags a managed block's BEGIN line with the PID of the process that must remove it. */
    public static final String OWNER_TOKEN = "pid=";

    private ManagedBlockOwnership() {
    }

    /** This process's owner tag, e.g. {@code "pid=12345"}. */
    public static String currentOwnerTag() {
        return OWNER_TOKEN + ProcessHandle.current().pid();
    }

    /**
     * Drop managed blocks bounded by {@code beginMarkerPrefix}/{@code endMarkerPrefix}
     * whose owning process has exited, or that predate owner tracking, so a session
     * that died before cleanup doesn't leave its block behind for good. Blocks owned
     * by live processes are kept untouched.
     */
    public static String reclaimOrphanedBlocks(String content, String beginMarkerPrefix,
            String endMarkerPrefix) {
        StringBuilder kept = new StringBuilder();
        int from = 0;
        int start;
        while ((start = content.indexOf(beginMarkerPrefix, from)) >= 0) {
            int end = blockEnd(content, start, beginMarkerPrefix, endMarkerPrefix);
            if (end < 0) break;
            if (ownerAlive(content.substring(start, end))) {
                kept.append(content, from, end);
            } else {
                // Drop the block together with the blank-line separator written before it.
                kept.append(content.substring(from, start).stripTrailing());
            }
            from = end;
        }
        return kept.append(content, from, content.length()).toString();
    }

    /** Strip every managed block bounded by {@code beginMarkerPrefix}/{@code endMarkerPrefix}. */
    public static String stripManagedBlocks(String content, String beginMarkerPrefix,
            String endMarkerPrefix) {
        String remaining = content == null ? "" : content;
        while (true) {
            int start = remaining.indexOf(beginMarkerPrefix);
            if (start < 0) return remaining.strip();
            int end = blockEnd(remaining, start, beginMarkerPrefix, endMarkerPrefix);
            if (end < 0) return remaining.strip();
            remaining = remaining.substring(0, start) + remaining.substring(end);
        }
    }

    /** Index just past the {@code "-->"} that closes the end marker, or -1 if unterminated. */
    private static int blockEnd(String content, int start, String beginMarkerPrefix,
            String endMarkerPrefix) {
        int endMarker = content.indexOf(endMarkerPrefix, start + beginMarkerPrefix.length());
        if (endMarker < 0) return -1;
        int close = content.indexOf("-->", endMarker);
        return close < 0 ? -1 : close + 3;
    }

    /**
     * Whether the block's BEGIN line names a still-living owner. Reads only the
     * contiguous digits immediately after {@link #OWNER_TOKEN} — never the rest of
     * the line — so trailing text after the pid (e.g. a closing {@code " -->"}) is
     * never swallowed into the number and mistaken for garbage that reads as dead.
     */
    private static boolean ownerAlive(String block) {
        String header = block.lines().findFirst().orElse("");
        int at = header.indexOf(OWNER_TOKEN);
        if (at < 0) return false;
        int digitsStart = at + OWNER_TOKEN.length();
        int digitsEnd = digitsStart;
        while (digitsEnd < header.length() && Character.isDigit(header.charAt(digitsEnd))) {
            digitsEnd++;
        }
        if (digitsEnd == digitsStart) return false;
        try {
            long pid = Long.parseLong(header.substring(digitsStart, digitsEnd));
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
