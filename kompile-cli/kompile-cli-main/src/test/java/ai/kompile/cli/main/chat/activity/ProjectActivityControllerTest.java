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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectActivityControllerTest {

    @Test
    void refreshPublishesSnapshotAndAppliesViewFilter() {
        AgentActivitySnapshot snapshot = snapshot("codex", "architect");
        AtomicInteger changes = new AtomicInteger();
        ProjectActivityController controller = controller(() -> snapshot);
        try {
            controller.start(changes::incrementAndGet);
            controller.captureNow();
            controller.show("architect");

            assertEquals(snapshot, controller.snapshot());
            assertEquals("architect", controller.filter());
            assertTrue(controller.isVisible());
            assertTrue(controller.title().contains("architect"));
            assertTrue(controller.content().contains("codex/architect"));
            assertTrue(changes.get() >= 1);

            controller.hide();
            assertFalse(controller.isVisible());
        } finally {
            controller.close();
        }
    }

    @Test
    void failedRefreshRetainsLastGoodSnapshotAndMarksViewStale() {
        AgentActivitySnapshot good = snapshot("claude", "reviewer");
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ProjectActivityController controller = controller(() -> {
            RuntimeException current = failure.get();
            if (current != null) throw current;
            return good;
        });
        try {
            controller.captureNow();
            failure.set(new IllegalStateException("coordination unavailable"));
            controller.captureNow();

            assertEquals(good, controller.snapshot());
            assertTrue(controller.compactStatus().contains("stale"));
            assertTrue(controller.content().contains("showing previous snapshot"));
            assertTrue(controller.content().contains("coordination unavailable"));
        } finally {
            controller.close();
        }
    }

    @Test
    void scheduledRefreshStartsAndClosePreventsFurtherCapture() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger changes = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        ProjectActivityController controller = controller(() -> {
            captures.incrementAndGet();
            return snapshot("qwen", "coder");
        });
        controller.start(() -> {
            changes.incrementAndGet();
            first.countDown();
        });
        assertTrue(first.await(2, TimeUnit.SECONDS));

        int hiddenChanges = changes.get();
        controller.captureNow();
        assertEquals(hiddenChanges, changes.get(),
                "an unchanged hidden snapshot must not request another TUI frame");

        controller.show("");
        controller.captureNow();
        assertTrue(changes.get() > hiddenChanges,
                "a visible dashboard deliberately refreshes elapsed-time rendering");

        controller.close();
        int afterClose = captures.get();
        controller.captureNow();
        controller.refresh();

        assertEquals(afterClose, captures.get());
    }

    private ProjectActivityController controller(
            java.util.function.Supplier<AgentActivitySnapshot> source) {
        return new ProjectActivityController(
                source, new AgentActivityRenderer(), "tool-session", () -> 100,
                20L, 100L, Executors.newSingleThreadScheduledExecutor());
    }

    private AgentActivitySnapshot snapshot(String agentName, String roleName) {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        AgentActivitySnapshot.AgentActivity agent =
                new AgentActivitySnapshot.AgentActivity(
                        "coord-session", "tool-session", agentName, roleName, "parent", "",
                        0, "Dashboard task", 123L, now.minusSeconds(10), now,
                        true, true, List.of(), List.of(), 0);
        return new AgentActivitySnapshot(
                now, Duration.ofMinutes(5), List.of(agent), List.of());
    }
}
