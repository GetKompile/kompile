/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemActivityCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void oneUserWideLaneExcludesOtherProjectsUntilRelease() {
        Path state = tempDir.resolve("system-state");
        SystemActivityCoordinator first = coordinator(state, "project-a", "session-a");
        SystemActivityCoordinator second = coordinator(state, "project-b", "session-b");
        try {
            SystemActivityCoordinator.ReservationResult admitted = first.reserve(
                    "codex-a", "BUILD", "bash", "build project A");
            SystemActivityCoordinator.ReservationResult blocked = second.reserve(
                    "codex-b", "CRAWL", "crawl_documents", "crawl project B");

            assertTrue(admitted.admitted());
            assertNotNull(admitted.activity());
            assertFalse(blocked.admitted());
            assertEquals(1, blocked.blockers().size());
            assertEquals("session-a", blocked.blockers().get(0).getSessionId());

            assertTrue(first.release(admitted.activity().getActivityId()));
            assertTrue(second.reserve("codex-b", "CRAWL", "crawl_documents",
                    "crawl project B").admitted());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void replacementSessionCanReleaseTerminalAsyncJobInSameProject() {
        Path state = tempDir.resolve("system-state");
        SystemActivityCoordinator starter = coordinator(state, "project-a", "session-a");
        SystemActivityCoordinator replacement = coordinator(state, "project-a", "session-b");
        try {
            CoordinationActivity activity = starter.reserve(
                    "codex", "CRAWL", "crawl_documents", "async crawl").activity();
            assertTrue(starter.attach(activity.getActivityId(), null, 0L, "crawl-job-42"));

            assertEquals(1, replacement.releaseByExternalId("crawl-job-42"));
            assertTrue(starter.queryActive().isEmpty());
        } finally {
            starter.close();
            replacement.close();
        }
    }

    @Test
    void exitedAttachedProcessIsEvictedWithoutWaitingForTtl() {
        Path state = tempDir.resolve("system-state");
        SystemActivityCoordinator coordinator = coordinator(state, "project-a", "session-a");
        try {
            CoordinationActivity activity = coordinator.reserve(
                    "codex", "TEST", "process", "background tests").activity();
            assertTrue(coordinator.attach(
                    activity.getActivityId(), "proc-1", Long.MAX_VALUE, null));

            assertTrue(coordinator.queryActive().isEmpty());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void concurrentReservationsProduceExactlyOneWinner() throws Exception {
        Path state = tempDir.resolve("system-state");
        SystemActivityCoordinator first = coordinator(state, "project-a", "session-a");
        SystemActivityCoordinator second = coordinator(state, "project-b", "session-b");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SystemActivityCoordinator.ReservationResult> one = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.reserve("one", "BUILD", "bash", "first");
            });
            Future<SystemActivityCoordinator.ReservationResult> two = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.reserve("two", "TEST", "bash", "second");
            });
            ready.await();
            start.countDown();

            List<SystemActivityCoordinator.ReservationResult> results = List.of(one.get(), two.get());
            assertEquals(1, results.stream().filter(
                    SystemActivityCoordinator.ReservationResult::admitted).count());
            assertEquals(1, first.queryActive().size());
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }
    }

    @Test
    void malformedActivityStateFailsReservationClosed() throws Exception {
        Path state = tempDir.resolve("system-state");
        Path activities = Files.createDirectories(state.resolve("activities"));
        Files.writeString(activities.resolve("broken.activity.json"), "{not-json");
        SystemActivityCoordinator coordinator = coordinator(state, "project-a", "session-a");
        try {
            SystemActivityCoordinator.ReservationResult result = coordinator.reserve(
                    "codex", "BUILD", "bash", "must not bypass malformed state");

            assertFalse(result.admitted());
            assertTrue(result.reason().contains("coordination unavailable"), result.reason());
        } finally {
            coordinator.close();
        }
    }

    private SystemActivityCoordinator coordinator(Path state, String project, String session) {
        return new SystemActivityCoordinator(state, tempDir.resolve(project), session,
                JsonUtils.standardMapper(), ignored -> { });
    }
}
