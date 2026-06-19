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
package ai.kompile.kclaw.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed store for {@link AgentTask}s under the kclaw workspace.
 *
 * <p>Each task is one JSON file at {@code <workspace>/tasks/<id>.json} (updated in place as the
 * task progresses), and the human-readable output artifact is written to
 * {@code <workspace>/outputs/<id>.md}. Mirrors the JSONL session layout used elsewhere in kclaw.
 */
public class AgentTaskStore {

    private final Path tasksDir;
    private final Path outputsDir;
    private final ObjectMapper mapper;

    public AgentTaskStore(String workspace, ObjectMapper mapper) throws IOException {
        Path ws = Path.of(workspace);
        this.tasksDir = ws.resolve("tasks");
        this.outputsDir = ws.resolve("outputs");
        Files.createDirectories(tasksDir);
        Files.createDirectories(outputsDir);
        this.mapper = mapper;
    }

    public Path outputsDir() {
        return outputsDir;
    }

    public synchronized void save(AgentTask task) {
        try {
            Path f = tasksDir.resolve(task.getId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), task);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task " + task.getId(), e);
        }
    }

    public Optional<AgentTask> get(String id) {
        Path f = tasksDir.resolve(id + ".json");
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(f.toFile(), AgentTask.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** All tasks, newest first. */
    public List<AgentTask> list() {
        if (!Files.isDirectory(tasksDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(tasksDir)) {
            List<AgentTask> tasks = new ArrayList<>();
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    tasks.add(mapper.readValue(p.toFile(), AgentTask.class));
                } catch (IOException ignored) {
                    // skip unreadable/partial records
                }
            });
            tasks.sort(Comparator.comparingLong(AgentTask::getCreatedAt).reversed());
            return tasks;
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean delete(String id) {
        try {
            boolean removed = Files.deleteIfExists(tasksDir.resolve(id + ".json"));
            Files.deleteIfExists(outputsDir.resolve(id + ".md"));
            return removed;
        } catch (IOException e) {
            return false;
        }
    }

    /** Write the output artifact (markdown) for a task; returns its path. */
    public Path writeOutput(AgentTask task) throws IOException {
        Path f = outputsDir.resolve(task.getId() + ".md");
        Files.writeString(f, renderArtifact(task), StandardCharsets.UTF_8);
        return f;
    }

    /** Render the markdown artifact body — also reused by tests. */
    static String renderArtifact(AgentTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Task: ").append(nullToEmpty(task.getTask())).append("\n\n");
        sb.append("- id: ").append(task.getId()).append('\n');
        sb.append("- engine: ").append(task.getEngine()).append('\n');
        if (task.getAgentId() != null) {
            sb.append("- agent: ").append(task.getAgentId()).append('\n');
        }
        sb.append("- status: ").append(task.getStatus()).append('\n');
        if (task.getError() != null) {
            sb.append("- error: ").append(task.getError()).append('\n');
        }
        sb.append("\n---\n\n").append(nullToEmpty(task.getOutput())).append('\n');
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
