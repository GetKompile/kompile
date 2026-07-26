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
 * A single git commit's metadata, as surfaced to the diff-browsing UI.
 * Populated from {@code git log --shortstat}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitCommit {
    private String hash;
    private String shortHash;
    private String author;
    private String email;
    /** Author date, ISO-8601 (e.g. {@code 2026-06-20T13:14:15-04:00}). */
    private String dateIso;
    private String subject;
    private int filesChanged;
    private long linesAdded;
    private long linesRemoved;
}
