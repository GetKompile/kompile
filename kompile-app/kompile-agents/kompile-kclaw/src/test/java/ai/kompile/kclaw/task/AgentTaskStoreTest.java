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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private AgentTaskStore store() throws IOException {
        return new AgentTaskStore(tempDir.toString(), mapper);
    }

    private AgentTask task(String id, long createdAt) {
        return AgentTask.builder()
                .id(id)
                .engine(TaskEngine.REACT)
                .agentId("jarvis")
                .task("do " + id)
                .status(AgentTask.Status.SUCCEEDED)
                .output("output " + id)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void saveAndGet_roundTripsAllFields() throws IOException {
        AgentTaskStore s = store();
        s.save(task("t1", 100));

        Optional<AgentTask> got = s.get("t1");
        assertTrue(got.isPresent());
        AgentTask t = got.get();
        assertEquals("do t1", t.getTask());
        assertEquals(TaskEngine.REACT, t.getEngine());
        assertEquals("jarvis", t.getAgentId());
        assertEquals(AgentTask.Status.SUCCEEDED, t.getStatus());
        assertEquals("output t1", t.getOutput());
        assertEquals(100, t.getCreatedAt());
    }

    @Test
    void get_missing_returnsEmpty() throws IOException {
        assertTrue(store().get("nope").isEmpty());
    }

    @Test
    void list_isNewestFirst() throws IOException {
        AgentTaskStore s = store();
        s.save(task("a", 100));
        s.save(task("b", 300));
        s.save(task("c", 200));

        List<AgentTask> list = s.list();
        assertEquals(3, list.size());
        assertEquals("b", list.get(0).getId());
        assertEquals("c", list.get(1).getId());
        assertEquals("a", list.get(2).getId());
    }

    @Test
    void writeOutput_createsMarkdownArtifactWithTaskAndOutput() throws IOException {
        AgentTaskStore s = store();
        AgentTask t = AgentTask.builder()
                .id("t2").engine(TaskEngine.KOMPILE_CLI).task("summarize the readme")
                .status(AgentTask.Status.SUCCEEDED).output("the summary text").createdAt(1).build();

        Path f = s.writeOutput(t);
        assertTrue(Files.exists(f));
        String content = Files.readString(f);
        assertTrue(content.contains("summarize the readme"), "artifact should contain the task");
        assertTrue(content.contains("the summary text"), "artifact should contain the output");
        assertTrue(content.contains("KOMPILE_CLI"), "artifact should record the engine");
    }

    @Test
    void delete_removesRecordAndArtifact() throws IOException {
        AgentTaskStore s = store();
        AgentTask t = task("t3", 1);
        s.save(t);
        s.writeOutput(t);

        assertTrue(s.delete("t3"));
        assertTrue(s.get("t3").isEmpty());
        assertFalse(s.delete("t3"), "second delete should report nothing removed");
    }
}
