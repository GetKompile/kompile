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

package ai.kompile.app.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SystemResourceBroadcaster}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemResourceBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SystemResourceBroadcaster broadcaster;

    /** True when ND4J native backend is available (needed for collectSystemResources). */
    private static boolean nd4jAvailable;

    static {
        try {
            Class.forName("org.nd4j.nativeblas.NativeOpsHolder");
            nd4jAvailable = true;
        } catch (Throwable t) {
            nd4jAvailable = false;
        }
    }

    @BeforeEach
    void setUp() {
        broadcaster = new SystemResourceBroadcaster(messagingTemplate);
        // @Value("${kompile.system.broadcast.enabled:true}") isn't processed outside Spring,
        // so broadcastEnabled stays false. Set it manually to match the default.
        ReflectionTestUtils.setField(broadcaster, "broadcastEnabled", true);
    }

    // ===== Subscriber management =====

    @Test
    void enableBroadcasting_incrementsSubscriberCount() {
        broadcaster.enableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(1);
        assertThat(broadcaster.isBroadcasting()).isTrue();
    }

    @Test
    void enableBroadcasting_twiceIncrementsTwice() {
        broadcaster.enableBroadcasting();
        broadcaster.enableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(2);
    }

    @Test
    void disableBroadcasting_decrementsSubscriberCount() {
        broadcaster.enableBroadcasting();
        broadcaster.disableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(0);
        assertThat(broadcaster.isBroadcasting()).isFalse();
    }

    @Test
    void stopBroadcasting_setsFlagFalse() {
        broadcaster.startBroadcasting();
        broadcaster.stopBroadcasting();
        assertThat(broadcaster.isBroadcasting()).isFalse();
    }

    @Test
    void startBroadcasting_setsFlagTrue() {
        broadcaster.startBroadcasting();
        assertThat(broadcaster.isBroadcasting()).isTrue();
    }

    @Test
    void initialState_notBroadcasting() {
        assertThat(broadcaster.isBroadcasting()).isFalse();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(0);
    }

    // ===== collectSystemResources =====
    // These tests require ND4J native backend. Skipped if not available.

    @Test
    void collectSystemResources_containsExpectedKeys() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        assertThat(resources).containsKeys(
                "timestamp", "cpu", "memory", "threads", "process", "disk", "system", "status"
        );
    }

    @Test
    void collectSystemResources_statusIsSuccess() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        assertThat(resources.get("status")).isEqualTo("success");
    }

    @Test
    void collectSystemResources_timestampIsRecent() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        long before = System.currentTimeMillis();
        Map<String, Object> resources = broadcaster.collectSystemResources();
        long after = System.currentTimeMillis();
        long ts = (long) resources.get("timestamp");
        assertThat(ts).isBetween(before, after);
    }

    @Test
    void collectSystemResources_cpuContainsProcessors() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        @SuppressWarnings("unchecked")
        Map<String, Object> cpu = (Map<String, Object>) resources.get("cpu");
        assertThat(cpu).containsKey("availableProcessors");
        assertThat((int) cpu.get("availableProcessors")).isGreaterThan(0);
    }

    @Test
    void collectSystemResources_memoryContainsHeap() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) resources.get("memory");
        assertThat(memory).containsKey("heap");
    }

    @Test
    void collectSystemResources_threadContainsCount() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        @SuppressWarnings("unchecked")
        Map<String, Object> threads = (Map<String, Object>) resources.get("threads");
        assertThat(threads).containsKey("threadCount");
        assertThat((int) threads.get("threadCount")).isGreaterThan(0);
    }

    @Test
    void collectSystemResources_processContainsPid() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        @SuppressWarnings("unchecked")
        Map<String, Object> process = (Map<String, Object>) resources.get("process");
        assertThat(process).containsKey("pid");
    }

    @Test
    void collectSystemResources_diskContainsPartitions() {
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        Map<String, Object> resources = broadcaster.collectSystemResources();
        @SuppressWarnings("unchecked")
        Map<String, Object> disk = (Map<String, Object>) resources.get("disk");
        assertThat(disk).containsKey("partitions");
    }

    // ===== broadcastSystemResources =====

    @Test
    void broadcastSystemResources_doesNothing_whenNoBroadcasting() {
        // Neither broadcasting flag set nor subscribers
        broadcaster.broadcastSystemResources();
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastSystemResources_sendsMessage_whenBroadcasting() {
        // Requires ND4J for collectSystemResources to complete successfully
        Assumptions.assumeTrue(nd4jAvailable, "ND4J native backend not available");
        broadcaster.startBroadcasting();
        broadcaster.broadcastSystemResources();
        verify(messagingTemplate).convertAndSend(
                eq(SystemResourceBroadcaster.TOPIC_SYSTEM_RESOURCES), any(Object.class));
    }

    @Test
    void broadcastSystemResources_doesNothing_whenMessagingTemplateNull() {
        SystemResourceBroadcaster noBroadcast = new SystemResourceBroadcaster(null);
        noBroadcast.startBroadcasting();
        // No exception expected
        assertThatCode(() -> noBroadcast.broadcastSystemResources()).doesNotThrowAnyException();
    }
}
