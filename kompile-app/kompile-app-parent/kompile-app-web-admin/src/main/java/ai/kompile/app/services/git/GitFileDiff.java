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

package ai.kompile.app.services.git;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The diff of a single file within a commit (or a single file's change at a
 * point in its history). The {@code unifiedDiff} is a clean unified-diff body
 * (starting at the {@code ---}/{@code +++} headers) so the frontend's
 * unified/side-by-side renderer can consume it directly, exactly like the
 * agent-change diffs from {@code /api/diff-index}.
 *
 * <p>The {@code commit*} / {@code author} / {@code dateIso} / {@code subject}
 * fields are populated only by the file-history endpoint (where each diff is
 * tied to a distinct commit); they are left null by the per-commit diff
 * endpoint.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitFileDiff {
    /** Post-change path (or pre-change path for deletions). */
    private String path;
    /** Pre-change path; differs from {@code path} only for renames/copies. */
    private String oldPath;
    /** ADDED, MODIFIED, DELETED, RENAMED, COPIED, or MODE. */
    private String changeType;
    private String unifiedDiff;
    private long linesAdded;
    private long linesRemoved;
    private boolean binary;

    // ── Commit context (file-history endpoint only) ──────────────────
    private String commitHash;
    private String commitShortHash;
    private String author;
    private String dateIso;
    private String subject;
}
