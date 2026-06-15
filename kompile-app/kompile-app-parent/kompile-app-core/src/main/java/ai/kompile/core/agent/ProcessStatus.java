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

package ai.kompile.core.agent;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the status of an agent process.
 */
@Getter
@Setter
public class ProcessStatus {

    private final String id;
    private final String agentName;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;
    private ProcessState state;
    private Long pid;
    private String command;
    private List<String> commandArgs;
    private int exitCode;
    private String errorMessage;
    @Setter(lombok.AccessLevel.NONE)
    private int linesReceived;
    @Setter(lombok.AccessLevel.NONE)
    private int chunksStreamed;
    @Setter(lombok.AccessLevel.NONE)
    private long bytesReceived;
    @Setter(lombok.AccessLevel.NONE)
    private List<String> modifiedFiles;
    @Setter(lombok.AccessLevel.NONE)
    @Getter(lombok.AccessLevel.NONE)
    private List<String> recentOutput;
    private Map<String, Object> metadata;

    private static final int MAX_RECENT_OUTPUT = 50;

    public ProcessStatus(String agentName, List<String> commandWithArgs) {
        this.id = UUID.randomUUID().toString();
        this.agentName = agentName;
        this.startTime = LocalDateTime.now();
        this.state = ProcessState.STARTING;
        this.commandArgs = new ArrayList<>(commandWithArgs);
        this.command = commandWithArgs.isEmpty() ? "" : commandWithArgs.get(0);
        this.modifiedFiles = new ArrayList<>();
        this.recentOutput = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * Add output line and track metrics.
     */
    public synchronized void addOutput(String line) {
        linesReceived++;
        bytesReceived += line.length();

        recentOutput.add(line);
        if (recentOutput.size() > MAX_RECENT_OUTPUT) {
            recentOutput.remove(0);
        }
    }

    /**
     * Mark a chunk as streamed.
     */
    public synchronized void chunkStreamed() {
        chunksStreamed++;
    }

    /**
     * Add a modified file.
     */
    public synchronized void addModifiedFile(String filePath) {
        if (!modifiedFiles.contains(filePath)) {
            modifiedFiles.add(filePath);
        }
    }

    /**
     * Calculate duration in milliseconds.
     */
    public long getDurationMs() {
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        return ChronoUnit.MILLIS.between(startTime, end);
    }

    /**
     * Check if process is still active.
     */
    public boolean isActive() {
        return state == ProcessState.STARTING ||
               state == ProcessState.RUNNING ||
               state == ProcessState.STREAMING;
    }

    /** Returns a defensive copy to avoid concurrent modification. */
    public List<String> getRecentOutput() {
        return new ArrayList<>(recentOutput);
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
}
