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

package ai.kompile.cli.main.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages background tasks for the chat REPL.
 * Supports backgrounding LLM responses with Ctrl+B, tracking completion,
 * and providing notifications for recently completed backgrounded tasks.
 */
public class BackgroundTaskManager {

    /**
     * Represents a background task (e.g., an LLM response running in background).
     */
    public static class BackgroundTask {
        private final String id;
        private final String description;
        private final Instant startedAt;
        private BackgroundTaskStatus status;
        private Instant completedAt;
        private String output;
        private Throwable error;

        public enum BackgroundTaskStatus {
            RUNNING,
            COMPLETED,
            FAILED,
            BACKGROUNDED
        }

        public BackgroundTask(String description) {
            this.id = java.util.UUID.randomUUID().toString().substring(0, 8);
            this.description = description;
            this.startedAt = Instant.now();
            this.status = BackgroundTaskStatus.RUNNING;
            this.output = "";
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        public BackgroundTaskStatus getStatus() {
            return status;
        }

        public void setStatus(BackgroundTaskStatus status) {
            this.status = status;
            if (status == BackgroundTaskStatus.COMPLETED || status == BackgroundTaskStatus.FAILED) {
                this.completedAt = Instant.now();
            }
        }

        public String getOutput() {
            return output;
        }

        public void appendOutput(String text) {
            this.output += text;
        }

        public Throwable getError() {
            return error;
        }

        public void setError(Throwable error) {
            this.error = error;
            this.status = BackgroundTaskStatus.FAILED;
            this.completedAt = Instant.now();
        }

        public String getElapsedTime() {
            Instant end = completedAt != null ? completedAt : Instant.now();
            long seconds = Duration.between(startedAt, end).getSeconds();
            if (seconds < 60) {
                return seconds + "s";
            } else if (seconds < 3600) {
                return (seconds / 60) + "m " + (seconds % 60) + "s";
            } else {
                return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            }
        }

        public String getFormattedDuration() {
            return getElapsedTime();
        }

        public String getStatusIcon() {
            switch (status) {
                case RUNNING: return "●";
                case BACKGROUNDED: return "◐";
                case COMPLETED: return "✓";
                case FAILED: return "✗";
                default: return "?";
            }
        }
    }

    private final Map<String, BackgroundTask> tasks;
    private final List<String> taskOrder;
    private BackgroundTask currentTask;
    private volatile boolean backgroundRequested = false;

    // Queue chain tracking
    private int queueChainTotal = 0;
    private int queueChainCurrent = 0;

    // Recently completed backgrounded tasks (for notification at next prompt)
    private final List<BackgroundTask> pendingNotifications = new CopyOnWriteArrayList<>();

    public BackgroundTaskManager() {
        this.tasks = new ConcurrentHashMap<>();
        this.taskOrder = new CopyOnWriteArrayList<>();
    }

    /**
     * Starts tracking a new background task.
     */
    public BackgroundTask startTask(String description) {
        BackgroundTask task = new BackgroundTask(description);
        tasks.put(task.getId(), task);
        taskOrder.add(task.getId());
        currentTask = task;
        backgroundRequested = false;
        return task;
    }

    public BackgroundTask getCurrentTask() {
        return currentTask;
    }

    /**
     * Signals that the current task should be backgrounded.
     */
    public void requestBackground() {
        backgroundRequested = true;
        if (currentTask != null) {
            currentTask.setStatus(BackgroundTask.BackgroundTaskStatus.BACKGROUNDED);
        }
    }

    public boolean isBackgroundRequested() {
        return backgroundRequested;
    }

    public void clearBackgroundRequest() {
        backgroundRequested = false;
    }

    /**
     * Marks the current task as completed. If it was backgrounded,
     * adds to pending notifications.
     */
    public void completeCurrentTask() {
        if (currentTask != null) {
            boolean wasBackgrounded = currentTask.getStatus() == BackgroundTask.BackgroundTaskStatus.BACKGROUNDED;
            currentTask.setStatus(BackgroundTask.BackgroundTaskStatus.COMPLETED);
            if (wasBackgrounded) {
                pendingNotifications.add(currentTask);
            }
            currentTask = null;
        }
    }

    /**
     * Returns and clears pending notifications for backgrounded tasks that completed.
     */
    public List<BackgroundTask> drainNotifications() {
        if (pendingNotifications.isEmpty()) {
            return List.of();
        }
        List<BackgroundTask> drained = new ArrayList<>(pendingNotifications);
        pendingNotifications.clear();
        return drained;
    }

    /**
     * Checks if there are pending completion notifications.
     */
    public boolean hasNotifications() {
        return !pendingNotifications.isEmpty();
    }

    // Queue chain tracking for "Processing 2/5" style progress
    public void startQueueChain(int totalMessages) {
        this.queueChainTotal = totalMessages;
        this.queueChainCurrent = 0;
    }

    public void advanceQueueChain() {
        this.queueChainCurrent++;
    }

    public int getQueueChainTotal() {
        return queueChainTotal;
    }

    public int getQueueChainCurrent() {
        return queueChainCurrent;
    }

    public boolean isInQueueChain() {
        return queueChainTotal > 0 && queueChainCurrent < queueChainTotal;
    }

    public void endQueueChain() {
        this.queueChainTotal = 0;
        this.queueChainCurrent = 0;
    }

    public BackgroundTask getTask(String id) {
        return tasks.get(id);
    }

    public List<BackgroundTask> getAllTasks() {
        List<BackgroundTask> result = new ArrayList<>();
        for (String id : taskOrder) {
            BackgroundTask task = tasks.get(id);
            if (task != null) {
                result.add(task);
            }
        }
        return result;
    }

    public List<BackgroundTask> getActiveTasks() {
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : getAllTasks()) {
            if (task.getStatus() == BackgroundTask.BackgroundTaskStatus.RUNNING ||
                task.getStatus() == BackgroundTask.BackgroundTaskStatus.BACKGROUNDED) {
                result.add(task);
            }
        }
        return result;
    }

    public List<BackgroundTask> getCompletedTasks() {
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : getAllTasks()) {
            if (task.getStatus() == BackgroundTask.BackgroundTaskStatus.COMPLETED ||
                task.getStatus() == BackgroundTask.BackgroundTaskStatus.FAILED) {
                result.add(task);
            }
        }
        return result;
    }

    public boolean removeTask(String id) {
        BackgroundTask task = tasks.get(id);
        if (task != null && task.getStatus() != BackgroundTask.BackgroundTaskStatus.RUNNING) {
            tasks.remove(id);
            taskOrder.remove(id);
            return true;
        }
        return false;
    }

    public void clearCompletedTasks() {
        List<String> toRemove = new ArrayList<>();
        for (String id : taskOrder) {
            BackgroundTask task = tasks.get(id);
            if (task != null && task.getStatus() == BackgroundTask.BackgroundTaskStatus.COMPLETED) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            tasks.remove(id);
            taskOrder.remove(id);
        }
    }

    /**
     * Gets the status of all tasks as a formatted string,
     * now including completed/failed task history.
     */
    public String getStatus() {
        List<BackgroundTask> all = getAllTasks();
        if (all.isEmpty()) {
            return "No background tasks";
        }

        List<BackgroundTask> active = getActiveTasks();
        List<BackgroundTask> completed = getCompletedTasks();

        StringBuilder sb = new StringBuilder();

        // Active tasks
        if (!active.isEmpty()) {
            sb.append("Active Tasks:\n");
            for (BackgroundTask task : active) {
                sb.append("  ").append(task.getStatusIcon())
                  .append(" [").append(task.getId()).append("] ")
                  .append(task.getDescription())
                  .append(" (").append(task.getElapsedTime()).append(")\n");
            }
        }

        // Completed/failed tasks
        if (!completed.isEmpty()) {
            if (!active.isEmpty()) sb.append("\n");
            sb.append("Recent Tasks:\n");
            // Show last 10 completed tasks
            int start = Math.max(0, completed.size() - 10);
            for (int i = start; i < completed.size(); i++) {
                BackgroundTask task = completed.get(i);
                sb.append("  ").append(task.getStatusIcon())
                  .append(" [").append(task.getId()).append("] ")
                  .append(task.getDescription())
                  .append(" (").append(task.getElapsedTime()).append(")");
                if (task.getStatus() == BackgroundTask.BackgroundTaskStatus.FAILED && task.getError() != null) {
                    sb.append(" — ").append(task.getError().getMessage());
                }
                // Show output preview for completed tasks
                if (task.getStatus() == BackgroundTask.BackgroundTaskStatus.COMPLETED
                        && task.getOutput() != null && !task.getOutput().isEmpty()) {
                    String preview = task.getOutput().replaceAll("\\s+", " ").trim();
                    if (preview.length() > 80) {
                        preview = preview.substring(0, 77) + "...";
                    }
                    sb.append("\n       ").append(preview);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
