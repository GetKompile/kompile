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
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.BackgroundTaskManager.BackgroundTask;
import ai.kompile.cli.main.chat.BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BackgroundTaskManager")
class BackgroundTaskManagerTest {

    private BackgroundTaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new BackgroundTaskManager();
    }

    @Nested
    @DisplayName("Task lifecycle")
    class TaskLifecycle {

        @Test
        void startTaskCreatesRunningTask() {
            BackgroundTask task = manager.startTask("test task");
            assertNotNull(task.getId());
            assertEquals(8, task.getId().length());
            assertEquals("test task", task.getDescription());
            assertEquals(BackgroundTaskStatus.RUNNING, task.getStatus());
            assertNotNull(task.getStartedAt());
            assertNull(task.getCompletedAt());
        }

        @Test
        void startTaskSetsCurrentTask() {
            BackgroundTask task = manager.startTask("task1");
            assertSame(task, manager.getCurrentTask());
        }

        @Test
        void completeCurrentTaskSetsCompleted() {
            BackgroundTask task = manager.startTask("task1");
            manager.completeCurrentTask();
            assertEquals(BackgroundTaskStatus.COMPLETED, task.getStatus());
            assertNotNull(task.getCompletedAt());
            assertNull(manager.getCurrentTask());
        }

        @Test
        void completeWhenNoCurrentTaskIsNoOp() {
            assertDoesNotThrow(() -> manager.completeCurrentTask());
        }

        @Test
        void setErrorSetsFailedStatusAndTimestamp() {
            BackgroundTask task = manager.startTask("failing task");
            RuntimeException error = new RuntimeException("boom");
            task.setError(error);
            assertEquals(BackgroundTaskStatus.FAILED, task.getStatus());
            assertNotNull(task.getCompletedAt());
            assertSame(error, task.getError());
        }

        @Test
        void appendOutputAccumulates() {
            BackgroundTask task = manager.startTask("task1");
            assertEquals("", task.getOutput());
            task.appendOutput("line1\n");
            task.appendOutput("line2\n");
            assertEquals("line1\nline2\n", task.getOutput());
        }
    }

    @Nested
    @DisplayName("Background request and notifications")
    class BackgroundAndNotifications {

        @Test
        void requestBackgroundSetsFlag() {
            manager.startTask("task1");
            assertFalse(manager.isBackgroundRequested());
            manager.requestBackground();
            assertTrue(manager.isBackgroundRequested());
        }

        @Test
        void requestBackgroundSetsTaskStatus() {
            BackgroundTask task = manager.startTask("task1");
            manager.requestBackground();
            assertEquals(BackgroundTaskStatus.BACKGROUNDED, task.getStatus());
        }

        @Test
        void clearBackgroundRequestResetsFlag() {
            manager.startTask("task1");
            manager.requestBackground();
            manager.clearBackgroundRequest();
            assertFalse(manager.isBackgroundRequested());
        }

        @Test
        void startTaskResetsBackgroundFlag() {
            manager.startTask("task1");
            manager.requestBackground();
            manager.startTask("task2");
            assertFalse(manager.isBackgroundRequested());
        }

        @Test
        void completedBackgroundedTaskGeneratesNotification() {
            manager.startTask("task1");
            manager.requestBackground();
            manager.completeCurrentTask();

            assertTrue(manager.hasNotifications());
            List<BackgroundTask> notifications = manager.drainNotifications();
            assertEquals(1, notifications.size());
            assertEquals("task1", notifications.get(0).getDescription());
        }

        @Test
        void completedForegroundTaskDoesNotGenerateNotification() {
            manager.startTask("task1");
            manager.completeCurrentTask();

            assertFalse(manager.hasNotifications());
            assertTrue(manager.drainNotifications().isEmpty());
        }

        @Test
        void drainNotificationsClearsQueue() {
            manager.startTask("task1");
            manager.requestBackground();
            manager.completeCurrentTask();

            manager.drainNotifications();
            assertFalse(manager.hasNotifications());
            assertTrue(manager.drainNotifications().isEmpty());
        }
    }

    @Nested
    @DisplayName("Queue chain tracking")
    class QueueChain {

        @Test
        void initiallyNotInQueueChain() {
            assertFalse(manager.isInQueueChain());
            assertEquals(0, manager.getQueueChainTotal());
            assertEquals(0, manager.getQueueChainCurrent());
        }

        @Test
        void startQueueChainSetsTotal() {
            manager.startQueueChain(5);
            assertTrue(manager.isInQueueChain());
            assertEquals(5, manager.getQueueChainTotal());
            assertEquals(0, manager.getQueueChainCurrent());
        }

        @Test
        void advanceQueueChainIncrementsCurrent() {
            manager.startQueueChain(3);
            manager.advanceQueueChain();
            assertEquals(1, manager.getQueueChainCurrent());
            manager.advanceQueueChain();
            assertEquals(2, manager.getQueueChainCurrent());
        }

        @Test
        void isInQueueChainFalseWhenCompleted() {
            manager.startQueueChain(2);
            manager.advanceQueueChain();
            manager.advanceQueueChain();
            assertFalse(manager.isInQueueChain());
        }

        @Test
        void endQueueChainResets() {
            manager.startQueueChain(5);
            manager.advanceQueueChain();
            manager.endQueueChain();
            assertFalse(manager.isInQueueChain());
            assertEquals(0, manager.getQueueChainTotal());
            assertEquals(0, manager.getQueueChainCurrent());
        }
    }

    @Nested
    @DisplayName("Task querying")
    class TaskQuerying {

        @Test
        void getTaskById() {
            BackgroundTask task = manager.startTask("task1");
            assertSame(task, manager.getTask(task.getId()));
        }

        @Test
        void getTaskReturnsNullForUnknownId() {
            assertNull(manager.getTask("nonexistent"));
        }

        @Test
        void getAllTasksReturnsInOrder() {
            BackgroundTask t1 = manager.startTask("first");
            manager.completeCurrentTask();
            BackgroundTask t2 = manager.startTask("second");

            List<BackgroundTask> all = manager.getAllTasks();
            assertEquals(2, all.size());
            assertEquals("first", all.get(0).getDescription());
            assertEquals("second", all.get(1).getDescription());
        }

        @Test
        void getActiveTasksFiltersCompleted() {
            manager.startTask("done");
            manager.completeCurrentTask();
            BackgroundTask running = manager.startTask("running");

            List<BackgroundTask> active = manager.getActiveTasks();
            assertEquals(1, active.size());
            assertSame(running, active.get(0));
        }

        @Test
        void getCompletedTasksFiltersRunning() {
            BackgroundTask done = manager.startTask("done");
            manager.completeCurrentTask();
            manager.startTask("running");

            List<BackgroundTask> completed = manager.getCompletedTasks();
            assertEquals(1, completed.size());
            assertSame(done, completed.get(0));
        }

        @Test
        void failedTasksAreIncludedInCompleted() {
            BackgroundTask failed = manager.startTask("failed");
            failed.setError(new RuntimeException("err"));

            List<BackgroundTask> completed = manager.getCompletedTasks();
            assertEquals(1, completed.size());
            assertEquals(BackgroundTaskStatus.FAILED, completed.get(0).getStatus());
        }
    }

    @Nested
    @DisplayName("Task removal")
    class TaskRemoval {

        @Test
        void removeCompletedTaskSucceeds() {
            BackgroundTask task = manager.startTask("task1");
            manager.completeCurrentTask();

            assertTrue(manager.removeTask(task.getId()));
            assertNull(manager.getTask(task.getId()));
            assertTrue(manager.getAllTasks().isEmpty());
        }

        @Test
        void removeRunningTaskFails() {
            BackgroundTask task = manager.startTask("task1");
            assertFalse(manager.removeTask(task.getId()));
            assertNotNull(manager.getTask(task.getId()));
        }

        @Test
        void clearCompletedTasksKeepsRunning() {
            BackgroundTask done = manager.startTask("done");
            manager.completeCurrentTask();
            BackgroundTask running = manager.startTask("running");

            manager.clearCompletedTasks();

            assertNull(manager.getTask(done.getId()));
            assertNotNull(manager.getTask(running.getId()));
            assertEquals(1, manager.getAllTasks().size());
        }
    }

    @Nested
    @DisplayName("Status formatting")
    class StatusFormatting {

        @Test
        void emptyManagerShowsNoTasks() {
            assertEquals("No background tasks", manager.getStatus());
        }

        @Test
        void activeTasksShownInStatus() {
            manager.startTask("building project");
            String status = manager.getStatus();
            assertTrue(status.contains("Active Tasks:"));
            assertTrue(status.contains("building project"));
        }

        @Test
        void completedTasksShownInRecentSection() {
            manager.startTask("done task");
            manager.completeCurrentTask();
            String status = manager.getStatus();
            assertTrue(status.contains("Recent Tasks:"));
            assertTrue(status.contains("done task"));
        }

        @Test
        void statusIconsAreCorrect() {
            BackgroundTask running = new BackgroundTask("r");
            assertEquals("●", running.getStatusIcon());

            running.setStatus(BackgroundTaskStatus.BACKGROUNDED);
            assertEquals("◐", running.getStatusIcon());

            running.setStatus(BackgroundTaskStatus.COMPLETED);
            assertEquals("✓", running.getStatusIcon());

            running.setStatus(BackgroundTaskStatus.FAILED);
            assertEquals("✗", running.getStatusIcon());
        }
    }

    @Nested
    @DisplayName("Elapsed time formatting")
    class ElapsedTimeFormatting {

        @Test
        void elapsedTimeForRunningTask() {
            BackgroundTask task = manager.startTask("task1");
            String elapsed = task.getElapsedTime();
            assertNotNull(elapsed);
            // Should be a short duration like "0s"
            assertTrue(elapsed.endsWith("s"));
        }

        @Test
        void formattedDurationDelegates() {
            BackgroundTask task = manager.startTask("task1");
            assertEquals(task.getElapsedTime(), task.getFormattedDuration());
        }
    }
}
