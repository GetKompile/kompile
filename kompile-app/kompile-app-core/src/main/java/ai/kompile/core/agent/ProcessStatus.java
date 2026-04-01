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
    private int linesReceived;
    private int chunksStreamed;
    private long bytesReceived;
    private List<String> modifiedFiles;
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

    // Getters and Setters

    public String getId() {
        return id;
    }

    public String getAgentName() {
        return agentName;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public ProcessState getState() {
        return state;
    }

    public void setState(ProcessState state) {
        this.state = state;
    }

    public Long getPid() {
        return pid;
    }

    public void setPid(Long pid) {
        this.pid = pid;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getCommandArgs() {
        return commandArgs;
    }

    public void setCommandArgs(List<String> commandArgs) {
        this.commandArgs = commandArgs;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getLinesReceived() {
        return linesReceived;
    }

    public int getChunksStreamed() {
        return chunksStreamed;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public List<String> getModifiedFiles() {
        return modifiedFiles;
    }

    public List<String> getRecentOutput() {
        return new ArrayList<>(recentOutput);
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
}
