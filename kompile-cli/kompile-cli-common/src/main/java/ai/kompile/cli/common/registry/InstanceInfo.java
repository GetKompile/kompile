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

import java.time.Instant;

/**
 * Describes a running Kompile application instance.
 */
public class InstanceInfo {
    private String name;
    private String type;  // "app", "staging", "agent"
    private int port;
    private long pid;
    private String jarPath;
    private String projectDir;
    private Instant startedAt;

    public InstanceInfo() {
    }

    public InstanceInfo(String name, String type, int port, long pid, String jarPath) {
        this.name = name;
        this.type = type;
        this.port = port;
        this.pid = pid;
        this.jarPath = jarPath;
        this.startedAt = Instant.now();
    }

    public InstanceInfo(String name, String type, int port, long pid, String jarPath, String projectDir) {
        this(name, type, port, pid, jarPath);
        this.projectDir = projectDir;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public long getPid() { return pid; }
    public void setPid(long pid) { this.pid = pid; }

    public String getJarPath() { return jarPath; }
    public void setJarPath(String jarPath) { this.jarPath = jarPath; }

    public String getProjectDir() { return projectDir; }
    public void setProjectDir(String projectDir) { this.projectDir = projectDir; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public String getUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] port=%d pid=%d", name, type, port, pid);
    }
}
