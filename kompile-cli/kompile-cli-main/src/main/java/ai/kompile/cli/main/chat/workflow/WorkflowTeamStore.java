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
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists workflow team definitions next to the project chat config as
 * {@code chat-workflows.json}. Like {@link ai.kompile.cli.main.chat.config.ChatProfiles},
 * this store never holds credentials and writes atomically via a temp file.
 */
public final class WorkflowTeamStore {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    private WorkflowTeamStore() {}

    public static Path path(Path projectRoot) {
        return ChatConfig.projectConfigPath(projectRoot).resolveSibling("chat-workflows.json");
    }

    public static List<WorkflowTeam> list(Path projectRoot) throws IOException {
        ObjectNode root = read(projectRoot);
        List<WorkflowTeam> result = new ArrayList<>();
        JsonNode workflows = root.path("workflows");
        List<String> names = new ArrayList<>();
        workflows.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        for (String name : names) {
            try {
                result.add(MAPPER.treeToValue(workflows.get(name), WorkflowTeam.class));
            } catch (IOException e) {
                throw new IllegalStateException("Could not parse workflow '" + name + "': " + e.getMessage(), e);
            }
        }
        return List.copyOf(result);
    }

    public static WorkflowTeam get(Path projectRoot, String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        JsonNode node = read(projectRoot).path("workflows").get(WorkflowTeam.key(name));
        return node == null ? null : MAPPER.treeToValue(node, WorkflowTeam.class);
    }

    /** Returns false rather than silently replacing a same-name workflow. */
    public static synchronized boolean save(Path projectRoot, WorkflowTeam workflow, boolean replace)
            throws IOException {
        ObjectNode root = read(projectRoot);
        ObjectNode workflows = object(root, "workflows");
        String key = WorkflowTeam.key(workflow.name());
        if (workflows.has(key) && !replace) return false;
        workflows.set(key, MAPPER.valueToTree(workflow));
        write(projectRoot, root);
        return true;
    }

    public static synchronized boolean delete(Path projectRoot, String name) throws IOException {
        ObjectNode root = read(projectRoot);
        ObjectNode workflows = object(root, "workflows");
        String key = WorkflowTeam.key(name);
        if (!workflows.has(key)) return false;
        workflows.remove(key);
        write(projectRoot, root);
        return true;
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        if (parent.has(field) && parent.get(field).isObject()) {
            return (ObjectNode) parent.get(field);
        }
        return parent.putObject(field);
    }

    private static ObjectNode read(Path projectRoot) throws IOException {
        Path file = path(projectRoot);
        if (!Files.exists(file)) return MAPPER.createObjectNode();
        try {
            return (ObjectNode) MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            throw new IOException("Could not parse " + file + ": " + e.getMessage(), e);
        }
    }

    private static void write(Path projectRoot, ObjectNode root) throws IOException {
        Path file = path(projectRoot);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
