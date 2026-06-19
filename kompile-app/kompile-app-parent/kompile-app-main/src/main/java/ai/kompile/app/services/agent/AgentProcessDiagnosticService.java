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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.ProcessState;
import ai.kompile.core.agent.ProcessStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for tracking and diagnosing agent processes.
 * <p>
 * Maintains history of process executions and provides real-time
 * status information for debugging and monitoring.
 */
@Service
public class AgentProcessDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessDiagnosticService.class);

    private static final int MAX_HISTORY = 20;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, ProcessStatus> activeProcesses = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, ProcessStatus> processHistory = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ProcessStatus> eldest) {
            return size() > MAX_HISTORY;
        }
    };

    /**
     * Start tracking a new process.
     */
    public ProcessStatus startProcess(String agentName, List<String> command) {
        ProcessStatus status = new ProcessStatus(agentName, command);
        activeProcesses.put(status.getId(), status);
        log.debug("Started tracking process {} for agent '{}'", status.getId(), agentName);
        return status;
    }

    /**
     * Mark process as started with PID.
     */
    public void processStarted(String processId, long pid) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status != null) {
            status.setPid(pid);
            status.setState(ProcessState.RUNNING);
            log.debug("Process {} started with PID {}", processId, pid);
        }
    }

    /**
     * Mark process as streaming output.
     */
    public void processStreaming(String processId) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status != null && status.getState() == ProcessState.RUNNING) {
            status.setState(ProcessState.STREAMING);
        }
    }

    /**
     * Record output received from process.
     */
    public void outputReceived(String processId, String line) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status != null) {
            status.addOutput(line);
            if (status.getState() == ProcessState.RUNNING) {
                status.setState(ProcessState.STREAMING);
            }
        }
    }

    /**
     * Record a chunk streamed to client.
     */
    public void chunkStreamed(String processId) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status != null) {
            status.chunkStreamed();
        }
    }

    /**
     * Record a file modification.
     */
    public void fileModified(String processId, String filePath) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status != null) {
            status.addModifiedFile(filePath);
        }
    }

    /**
     * Mark process as completed.
     */
    public void processCompleted(String processId, int exitCode) {
        ProcessStatus status = activeProcesses.remove(processId);
        if (status != null) {
            status.setEndTime(LocalDateTime.now());
            status.setExitCode(exitCode);
            status.setState(exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED);

            synchronized (processHistory) {
                processHistory.put(processId, status);
            }

            log.debug("Process {} completed with exit code {}", processId, exitCode);
        }
    }

    /**
     * Mark process as failed.
     */
    public void processFailed(String processId, String errorMessage) {
        ProcessStatus status = activeProcesses.remove(processId);
        if (status != null) {
            status.setEndTime(LocalDateTime.now());
            status.setState(ProcessState.FAILED);
            status.setErrorMessage(errorMessage);

            synchronized (processHistory) {
                processHistory.put(processId, status);
            }

            log.debug("Process {} failed: {}", processId, errorMessage);
        }
    }

    /**
     * Mark process as timed out.
     */
    public void processTimedOut(String processId) {
        ProcessStatus status = activeProcesses.remove(processId);
        if (status != null) {
            status.setEndTime(LocalDateTime.now());
            status.setState(ProcessState.TIMEOUT);
            status.setErrorMessage("Process timed out");

            synchronized (processHistory) {
                processHistory.put(processId, status);
            }

            log.debug("Process {} timed out", processId);
        }
    }

    /**
     * Cancel a running process.
     */
    public void processCancelled(String processId) {
        ProcessStatus status = activeProcesses.remove(processId);
        if (status != null) {
            status.setEndTime(LocalDateTime.now());
            status.setState(ProcessState.CANCELLED);

            synchronized (processHistory) {
                processHistory.put(processId, status);
            }

            log.debug("Process {} cancelled", processId);
        }
    }

    /**
     * Get current active process (if any).
     */
    public Optional<ProcessStatus> getCurrentProcess() {
        return activeProcesses.values().stream().findFirst();
    }

    /**
     * Get specific process by ID.
     */
    public Optional<ProcessStatus> getProcess(String processId) {
        ProcessStatus status = activeProcesses.get(processId);
        if (status == null) {
            synchronized (processHistory) {
                status = processHistory.get(processId);
            }
        }
        return Optional.ofNullable(status);
    }

    /**
     * Get process history.
     */
    public List<ProcessStatus> getHistory() {
        synchronized (processHistory) {
            return new ArrayList<>(processHistory.values());
        }
    }

    /**
     * Clear process history.
     */
    public void clearHistory() {
        synchronized (processHistory) {
            processHistory.clear();
        }
        log.info("Process history cleared");
    }

    /**
     * Check if there's an active process.
     */
    public boolean hasActiveProcess() {
        return !activeProcesses.isEmpty();
    }

    /**
     * Get diagnostic summary.
     */
    public DiagnosticSummary getSummary() {
        DiagnosticSummary summary = new DiagnosticSummary();

        Optional<ProcessStatus> current = getCurrentProcess();
        if (current.isPresent()) {
            ProcessStatus status = current.get();
            summary.setHasActiveProcess(true);
            summary.setActiveProcessId(status.getId());
            summary.setActiveAgentName(status.getAgentName());
            summary.setActiveProcessState(status.getState());
            summary.setActiveProcessDurationMs(status.getDurationMs());
            summary.setActiveProcessLinesReceived(status.getLinesReceived());
        }

        synchronized (processHistory) {
            summary.setRecentProcessCount(processHistory.size());
            summary.setFailedProcessCount((int) processHistory.values().stream()
                    .filter(p -> p.getState() == ProcessState.FAILED || p.getState() == ProcessState.TIMEOUT)
                    .count());

            processHistory.values().stream()
                    .reduce((first, second) -> second)
                    .ifPresent(last -> {
                        if (last.getEndTime() != null) {
                            summary.setLastProcessTime(last.getEndTime().format(FORMATTER));
                        }
                        if (last.getState() == ProcessState.FAILED && last.getErrorMessage() != null) {
                            summary.setLastError(last.getErrorMessage());
                        }
                    });
        }

        return summary;
    }

    /**
     * Get full diagnostic report.
     */
    public FullDiagnosticReport getFullReport() {
        FullDiagnosticReport report = FullDiagnosticReport.builder()
                .summary(getSummary())
                .currentProcess(getCurrentProcess().orElse(null))
                .recentProcesses(getHistory())
                .build();
        return report;
    }

}
