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

package ai.kompile.cli.common.registry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Describes a running Kompile application instance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceInfo {
    private String name;
    private String type;  // "app", "staging", "agent"
    private int port;
    private long pid;
    private String jarPath;
    private String projectDir;
    private Instant startedAt;

    public String getUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] port=%d pid=%d", name, type, port, pid);
    }
}
