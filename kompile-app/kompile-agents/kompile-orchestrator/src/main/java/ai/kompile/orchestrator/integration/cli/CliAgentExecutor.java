/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.integration.cli;

import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.AgentLogWriter;
import ai.kompile.orchestrator.model.event.LlmTokenEvent;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executor for CLI-based LLM agents with proper process management.
 *
 * <p>Features:
 * <ul>
 *   <li>Process spawning with configurable flags</li>
 *   <li>Real-time output streaming with JSON parsing</li>
 *   <li>Proper process termination (graceful + forceful)</li>
 *   <li>Timeout handling</li>
 *   <li>Process state tracking</li>
 *   <li>Event publishing for real-time updates</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CliAgentExecutor {

    private final ClaudeStreamParser streamParser;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${kompile.orchestrator.cli.default-timeout-seconds:300}")
    private int defaultTimeoutSeconds;


    // Track running processes
    private final Map<String, ProcessInfo> runningProcesses = new ConcurrentHashMap<>();

    // Thread pool for async process execution
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cli-agent-executor");
        t.setDaemon(true);
        return t;
    });

    // Process states
    public enum ProcessState {
        STARTING,
        RUNNING,
        STREAMING,
        COMPLETED,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    /**
     * Information about a running process.
     */
    @Data
    public static class ProcessInfo {
        private final String id;
        private final String agentName;
        private final LocalDateTime startTime;
        private volatile LocalDateTime endTime;
        private volatile ProcessState state;
        private Process process;
        private Long pid;
        private List<String> command;
        private volatile int exitCode;
        private volatile String errorMessage;
        private final AtomicInteger linesReceived = new AtomicInteger(0);
        private final AtomicInteger tokensStreamed = new AtomicInteger(0);
        private final List<String> recentOutput = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> modifiedFiles = ConcurrentHashMap.newKeySet();
        private Sinks.Many<String> outputSink;

        public ProcessInfo(String id, String agentName, List<String> command) {
            this.id = id;
            this.agentName = agentName;
            this.command = command;
            this.startTime = LocalDateTime.now();
            this.state = ProcessState.STARTING;
        }

        public void addRecentOutput(String line) {
            recentOutput.add(line);
            if (recentOutput.size() > 50) {
                recentOutput.remove(0);
            }
        }
    }

    /**
     * Execution request for a CLI agent.
     */
    @Data
    @Builder
    public static class ExecutionRequest {
        private String agentName;
        private String prompt;
        private String workingDirectory;
        private boolean skipPermissions;
        private int timeoutSeconds;
        private String orchestratorInstanceId;
        private Long sessionId;
    }

    /**
     * Execution result.
     */
    @Data
    @Builder
    public static class ExecutionResult {
        private String processId;
        private ProcessState state;
        private String output;
        private int exitCode;
        private String errorMessage;
        private long durationMs;
        private Double costUsd;
        private Integer numTurns;
        private Set<String> modifiedFiles;
    }

    /**
     * Execute a CLI agent synchronously.
     */
    public ExecutionResult execute(ExecutionRequest request) {
        String processId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        // Get agent config
        CliAgentConfig agent = CliAgentConfig.getByName(request.getAgentName())
                .orElse(CliAgentConfig.getDefault());

        // Check availability
        if (!agent.checkAvailability()) {
            return ExecutionResult.builder()
                    .processId(processId)
                    .state(ProcessState.FAILED)
                    .errorMessage("Agent CLI not available: " + agent.getCommand())
                    .build();
        }

        // Build command
        List<String> command = agent.buildCommand(
                request.getPrompt(),
                request.isSkipPermissions(),
                request.getWorkingDirectory()
        );

        log.info("Executing CLI agent: {} with command: {}", agent.getName(),
                String.join(" ", command).substring(0, Math.min(200, String.join(" ", command).length())));

        // Create process info
        ProcessInfo processInfo = new ProcessInfo(processId, agent.getName(), command);
        processInfo.setOutputSink(Sinks.many().multicast().onBackpressureBuffer());
        runningProcesses.put(processId, processInfo);

        StringBuilder fullOutput = new StringBuilder();
        Double costUsd = null;
        Integer numTurns = null;
        Integer durationMs = null;

        AgentLogWriter logWriter = openLogWriter(request, agent.getName(), processId);

        try {
            // Build process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            if (request.getWorkingDirectory() != null) {
                pb.directory(new File(request.getWorkingDirectory()));
            }

            // Add environment
            pb.environment().putAll(agent.safeEnvironment());

            // Start process
            Process process = pb.start();
            processInfo.setProcess(process);
            processInfo.setPid(process.pid());
            processInfo.setState(ProcessState.RUNNING);

            log.debug("Process {} started with PID {}", processId, process.pid());

            startLogWriter(logWriter,
                    new AgentLogWriter.AgentRunContext(
                            request.getSessionId(),
                            command,
                            request.getWorkingDirectory(),
                            process.pid()));

            // Read output with timeout
            int timeoutSecs = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : defaultTimeoutSeconds;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                processInfo.setState(ProcessState.STREAMING);

                String line;
                while ((line = reader.readLine()) != null) {
                    processInfo.getLinesReceived().incrementAndGet();
                    processInfo.addRecentOutput(line);
                    appendLogLine(logWriter, AgentLogRecord.Stream.STDOUT, line);

                    // Parse with ClaudeStreamParser
                    ClaudeStreamParser.ParseResult result = streamParser.parseLine(processId, line);

                    if (result != null) {
                        // Stream the content
                        if (result.textContent() != null) {
                            fullOutput.append(result.textContent());
                            processInfo.getTokensStreamed().incrementAndGet();
                            processInfo.getOutputSink().tryEmitNext(result.textContent());

                            // Publish token event
                            if (request.getSessionId() != null) {
                                eventPublisher.publishEvent(LlmTokenEvent.token(
                                        this,
                                        request.getOrchestratorInstanceId(),
                                        request.getSessionId(),
                                        result.textContent(),
                                        processInfo.getTokensStreamed().get(),
                                        agent.getName()
                                ));
                            }
                        }

                        // Track stats from result event
                        if (result.isResult()) {
                            durationMs = result.durationMs();
                            costUsd = result.costUsd();
                            numTurns = result.numTurns();
                        }

                        // Track modified files
                        if (result.toolName() != null) {
                            Set<String> files = streamParser.getModifiedFiles(processId);
                            processInfo.getModifiedFiles().addAll(files);
                        }
                    }
                }
            }

            // Wait for process completion
            boolean completed = process.waitFor(timeoutSecs, TimeUnit.SECONDS);

            if (!completed) {
                // Timeout - kill process
                log.warn("Process {} timed out after {} seconds, killing", processId, timeoutSecs);
                terminateProcess(processId, true);
                processInfo.setState(ProcessState.TIMEOUT);
                processInfo.setErrorMessage("Process timed out after " + timeoutSecs + " seconds");
            } else {
                processInfo.setExitCode(process.exitValue());
                if (process.exitValue() == 0) {
                    processInfo.setState(ProcessState.COMPLETED);
                } else {
                    processInfo.setState(ProcessState.FAILED);
                    processInfo.setErrorMessage("Process exited with code: " + process.exitValue());
                }
            }

            processInfo.setEndTime(LocalDateTime.now());

            // Complete the sink
            processInfo.getOutputSink().tryEmitComplete();

            // Publish completion event
            if (request.getSessionId() != null) {
                eventPublisher.publishEvent(LlmTokenEvent.complete(
                        this,
                        request.getOrchestratorInstanceId(),
                        request.getSessionId(),
                        processInfo.getTokensStreamed().get(),
                        agent.getName()
                ));
            }

        } catch (Exception e) {
            log.error("Error executing CLI agent {}: {}", agent.getName(), e.getMessage(), e);
            processInfo.setState(ProcessState.FAILED);
            processInfo.setErrorMessage(e.getMessage());
            processInfo.setEndTime(LocalDateTime.now());
            processInfo.getOutputSink().tryEmitError(e);
        } finally {
            // Cleanup
            streamParser.clearSession(processId);
            runningProcesses.remove(processId);
            finishLogWriter(logWriter,
                    new AgentLogWriter.AgentRunResult(
                            processInfo.getState() == null ? null : processInfo.getState().name(),
                            processInfo.getExitCode(),
                            processInfo.getErrorMessage(),
                            costUsd,
                            numTurns));
        }

        long endTime = System.currentTimeMillis();

        return ExecutionResult.builder()
                .processId(processId)
                .state(processInfo.getState())
                .output(fullOutput.toString())
                .exitCode(processInfo.getExitCode())
                .errorMessage(processInfo.getErrorMessage())
                .durationMs(endTime - startTime)
                .costUsd(costUsd)
                .numTurns(numTurns)
                .modifiedFiles(processInfo.getModifiedFiles())
                .build();
    }

    /**
     * Execute a CLI agent asynchronously with streaming output.
     */
    public Flux<String> executeStreaming(ExecutionRequest request) {
        String processId = UUID.randomUUID().toString();

        // Get agent config
        CliAgentConfig agent = CliAgentConfig.getByName(request.getAgentName())
                .orElse(CliAgentConfig.getDefault());

        // Check availability
        if (!agent.checkAvailability()) {
            return Flux.error(new IllegalStateException("Agent CLI not available: " + agent.getCommand()));
        }

        // Build command
        List<String> command = agent.buildCommand(
                request.getPrompt(),
                request.isSkipPermissions(),
                request.getWorkingDirectory()
        );

        // Create process info with sink
        ProcessInfo processInfo = new ProcessInfo(processId, agent.getName(), command);
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        processInfo.setOutputSink(sink);
        runningProcesses.put(processId, processInfo);

        AgentLogWriter logWriter = openLogWriter(request, agent.getName(), processId);

        // Execute in background
        executorService.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);

                if (request.getWorkingDirectory() != null) {
                    pb.directory(new File(request.getWorkingDirectory()));
                }

                pb.environment().putAll(agent.safeEnvironment());

                Process process = pb.start();
                processInfo.setProcess(process);
                processInfo.setPid(process.pid());
                processInfo.setState(ProcessState.STREAMING);

                startLogWriter(logWriter,
                        new AgentLogWriter.AgentRunContext(
                                request.getSessionId(),
                                command,
                                request.getWorkingDirectory(),
                                process.pid()));

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Check if cancelled
                        if (!runningProcesses.containsKey(processId)) {
                            log.debug("Process {} was cancelled", processId);
                            break;
                        }

                        appendLogLine(logWriter, AgentLogRecord.Stream.STDOUT, line);

                        ClaudeStreamParser.ParseResult result = streamParser.parseLine(processId, line);
                        if (result != null && result.textContent() != null) {
                            sink.tryEmitNext(result.textContent());
                        }
                    }
                }

                int timeoutSecs = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : defaultTimeoutSeconds;
                boolean completed = process.waitFor(timeoutSecs, TimeUnit.SECONDS);

                if (!completed) {
                    terminateProcess(processId, true);
                    processInfo.setState(ProcessState.TIMEOUT);
                    sink.tryEmitError(new TimeoutException("Process timed out"));
                } else {
                    processInfo.setExitCode(process.exitValue());
                    processInfo.setState(process.exitValue() == 0 ? ProcessState.COMPLETED : ProcessState.FAILED);
                    sink.tryEmitComplete();
                }

            } catch (Exception e) {
                log.error("Error in streaming execution: {}", e.getMessage(), e);
                processInfo.setState(ProcessState.FAILED);
                sink.tryEmitError(e);
            } finally {
                streamParser.clearSession(processId);
                runningProcesses.remove(processId);
                finishLogWriter(logWriter,
                        new AgentLogWriter.AgentRunResult(
                                processInfo.getState() == null ? null : processInfo.getState().name(),
                                processInfo.getExitCode(),
                                processInfo.getErrorMessage(),
                                null,
                                null));
            }
        });

        return sink.asFlux();
    }

    /**
     * Opens a per-run log writer rooted at {@code ~/.kompile/logs/agents}. Returns
     * {@code null} if the filesystem is unwritable; callers tolerate a null writer
     * so aggregation failures never break a live agent run.
     */
    private AgentLogWriter openLogWriter(ExecutionRequest request, String agentName, String processId) {
        try {
            return new AgentLogWriter(request.getOrchestratorInstanceId(), agentName, processId);
        } catch (Exception e) {
            log.warn("Failed to open agent log file for process {}: {}", processId, e.getMessage());
            return null;
        }
    }

    private void startLogWriter(AgentLogWriter writer, AgentLogWriter.AgentRunContext ctx) {
        if (writer == null) return;
        try {
            writer.writeStart(ctx);
        } catch (Exception e) {
            log.warn("Failed to write agent log start record: {}", e.getMessage());
        }
    }

    private void appendLogLine(AgentLogWriter writer, AgentLogRecord.Stream stream, String line) {
        if (writer == null) return;
        try {
            writer.writeLine(stream, line);
        } catch (Exception e) {
            // Downgrade to debug — losing a line is preferable to interrupting the agent
            log.debug("Failed to append agent log line: {}", e.getMessage());
        }
    }

    private void finishLogWriter(AgentLogWriter writer, AgentLogWriter.AgentRunResult result) {
        if (writer == null) return;
        try {
            writer.writeEnd(result);
        } catch (Exception e) {
            log.warn("Failed to write agent log end record: {}", e.getMessage());
        } finally {
            writer.close();
        }
    }

    /**
     * Cancel a running process.
     */
    public boolean cancelProcess(String processId) {
        ProcessInfo info = runningProcesses.remove(processId);
        if (info == null) {
            return false;
        }

        log.info("Cancelling process {}", processId);
        info.setState(ProcessState.CANCELLED);

        if (info.getOutputSink() != null) {
            info.getOutputSink().tryEmitComplete();
        }

        return terminateProcess(processId, info, false);
    }

    /**
     * Terminate a process (gracefully first, then forcefully if needed).
     */
    private boolean terminateProcess(String processId, boolean force) {
        ProcessInfo info = runningProcesses.get(processId);
        if (info == null) return false;
        return terminateProcess(processId, info, force);
    }

    private boolean terminateProcess(String processId, ProcessInfo info, boolean force) {
        Process process = info.getProcess();
        if (process == null || !process.isAlive()) {
            return true;
        }

        try {
            if (!force) {
                // Graceful termination
                process.destroy();
                boolean terminated = process.waitFor(2, TimeUnit.SECONDS);
                if (terminated) {
                    log.debug("Process {} terminated gracefully", processId);
                    return true;
                }
            }

            // Force kill
            process.destroyForcibly();
            boolean killed = process.waitFor(1, TimeUnit.SECONDS);
            log.debug("Process {} force killed: {}", processId, killed);
            return killed;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while terminating process {}", processId);
            return false;
        }
    }

    /**
     * Get information about a running process.
     */
    public Optional<ProcessInfo> getProcessInfo(String processId) {
        return Optional.ofNullable(runningProcesses.get(processId));
    }

    /**
     * Get all running processes.
     */
    public Collection<ProcessInfo> getRunningProcesses() {
        return Collections.unmodifiableCollection(runningProcesses.values());
    }

    /**
     * Check available agents.
     */
    public List<CliAgentConfig> getAvailableAgents() {
        List<CliAgentConfig> available = new ArrayList<>();
        for (CliAgentConfig agent : CliAgentConfig.getAllAgents()) {
            if (agent.checkAvailability()) {
                agent.detectMcpCapabilities();
                available.add(agent);
            }
        }
        return available;
    }

    /**
     * Cleanup on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CLI agent executor, terminating {} running processes", runningProcesses.size());

        for (String processId : new ArrayList<>(runningProcesses.keySet())) {
            cancelProcess(processId);
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
